package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.access.AccessContextResolver;
import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.access.DataScopePolicy;
import com.nekocong.text2sql.access.DataScopePolicyEngine;
import com.nekocong.text2sql.access.DataScopeRule;
import com.nekocong.text2sql.access.EffectiveAccess;
import com.nekocong.text2sql.access.PolicyDecision;
import com.nekocong.text2sql.access.PrincipalAttribute;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.user.AppUser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The data-scope policy admin API: list, upsert, delete, validation against live metadata, audit and
 * the next-request effect of a stored rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessControlDataScopeTest {

    private static final String PASSWORD = "secret123";

    private static final String BASE = "/api/admin/access-control";

    private static final String DATA_SCOPES = BASE + "/data-scopes";

    private static final String DATASOURCE = "campus";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccessControlRepository access;

    @Autowired
    private AccessContextResolver resolver;

    @Autowired
    private DatabaseClient db;

    private final DataScopePolicyEngine engine = new DataScopePolicyEngine();

    private WebTestClient client;
    private String adminToken;
    private String adminId;
    private String customRoleId;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        String username = unique("admin");
        AppUser admin =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Admin", Roles.ADMIN).block();
        access.assignRoleByCode(admin.id(), Roles.ADMIN).block();
        adminId = admin.id();
        adminToken = TestAuth.login(client, username, PASSWORD);

        // A fresh, non built-in role per test: the H2 database is shared across test contexts, so the
        // seeded roles must not be edited in place.
        customRoleId = createRole();
    }

    @Test
    void listIsEmptyThenReturnsTheUpsertedRule() {
        assertThat(listRules()).isEmpty();

        Map<String, Object> created = upsertRule("score", "FILTER", "student_id", "studentId");
        String id = String.valueOf(created.get("id"));
        assertThat(id).isNotBlank();
        assertThat(created.get("roleId")).isEqualTo(customRoleId);
        assertThat(created.get("datasourceId")).isEqualTo(DATASOURCE);
        assertThat(created.get("tableName")).isEqualTo("score");
        assertThat(created.get("policy")).isEqualTo("FILTER");
        assertThat(created.get("columnName")).isEqualTo("student_id");
        assertThat(created.get("principalAttribute")).isEqualTo("studentId");

        List<Map<String, Object>> rules = listRules();
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).get("id")).isEqualTo(id);

        // The datasourceId filter narrows the listing; an unknown datasource simply matches nothing.
        assertThat(listRules("campus")).hasSize(1);
        assertThat(listRules("nope")).isEmpty();
    }

    @Test
    void reUpsertingTheSameKeyKeepsTheIdAndChangesThePolicy() {
        String id = String.valueOf(upsertRule("score", "FILTER", "student_id", "studentId").get("id"));

        Map<String, Object> all = upsertRule("score", "ALL", null, null);
        assertThat(all.get("id")).isEqualTo(id);
        assertThat(all.get("policy")).isEqualTo("ALL");
        assertThat(all.get("columnName")).isNull();
        assertThat(all.get("principalAttribute")).isNull();

        List<Map<String, Object>> rules = listRules();
        assertThat(rules)
                .as("one policy per role and table, not a second row")
                .hasSize(1);
        assertThat(rules.get(0).get("policy")).isEqualTo("ALL");

        // DENY also carries no predicate, and switching back to FILTER works again.
        Map<String, Object> denied = upsertRule("score", "DENY", null, null);
        assertThat(denied.get("id")).isEqualTo(id);
        assertThat(denied.get("policy")).isEqualTo("DENY");

        Map<String, Object> filteredAgain = upsertRule("score", "FILTER", "class_id", "classIds");
        assertThat(filteredAgain.get("id")).isEqualTo(id);
        assertThat(filteredAgain.get("columnName")).isEqualTo("class_id");
        assertThat(filteredAgain.get("principalAttribute")).isEqualTo("classIds");
        assertThat(listRules()).hasSize(1);
    }

    @Test
    void aFilterWithoutAColumnOrAttributeIsRejected() {
        expectedError("score", "FILTER", null, null, 400);
        expectedError("score", "FILTER", "student_id", null, 400);
        expectedError("score", "FILTER", null, "studentId", 400);
        assertThat(listRules()).isEmpty();
        assertThat(auditRows("data_scope.upsert", null, "SUCCESS")).isZero();
        assertThat(auditRows("data_scope.upsert", null, "DENIED")).isZero();
    }

    @Test
    void anAllOrDenyPolicyThatCarriesAColumnIsRejected() {
        expectedError("score", "ALL", "student_id", null, 400);
        expectedError("score", "DENY", null, "studentId", 400);
        expectedError("score", "ALL", "student_id", "studentId", 400);
        assertThat(listRules()).isEmpty();
    }

    @Test
    void anInvalidPolicyOrRawSqlIsRejected() {
        expectedError("score", "EVERYTHING", null, null, 400);
        expectedError("score", null, null, null, 400);

        // A raw predicate, a qualified name or a quoted identifier never reaches the database.
        expectedError("score WHERE 1=1", "ALL", null, null, 400);
        expectedError("campus.score", "ALL", null, null, 400);
        expectedError("\"score\"", "ALL", null, null, 400);
        expectedError("score", "FILTER", "student_id = 1 OR 1=1", "studentId", 400);
        expectedError("score", "FILTER", "student-id", "studentId", 400);
        assertThat(listRules()).isEmpty();
    }

    @Test
    void anUnknownTableOrColumnOrAttributeIsUnprocessableAndAudited() {
        assertThat(error("no_such_table", "ALL", null, null, 422))
                .isEqualTo("表不存在：no_such_table");
        assertThat(error("score", "FILTER", "no_such_column", "studentId", 422))
                .isEqualTo("列不存在：no_such_column");
        assertThat(error("score", "FILTER", "student_id", "departmentId", 422))
                .isEqualTo("未知主体属性：departmentId");
        // The column is validated against the requested table, not against any table of the schema.
        assertThat(error("class", "FILTER", "student_id", "studentId", 422))
                .isEqualTo("列不存在：student_id");

        assertThat(listRules()).isEmpty();
        assertThat(auditRows("data_scope.upsert", null, "DENIED")).isEqualTo(4);
        assertThat(auditRows("data_scope.upsert", null, "SUCCESS")).isZero();
    }

    @Test
    void anUnknownRoleOrDatasourceIsNotFound() {
        String missingRole = UUID.randomUUID().toString();

        client.put()
                .uri(DATA_SCOPES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(missingRole, DATASOURCE, "score", "ALL", null, null))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("角色不存在");

        client.put()
                .uri(DATA_SCOPES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(customRoleId, "no-such-ds", "score", "ALL", null, null))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("数据源不存在");

        client.get()
                .uri(uri -> uri.path(DATA_SCOPES).queryParam("roleId", missingRole).build())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("角色不存在");

        assertThat(auditRows("data_scope.upsert", null, "SUCCESS")).isZero();
    }

    @Test
    void aStudentCannotReadOrWriteDataScopes() {
        String username = unique("student");
        AppUser student =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Student", Roles.STUDENT)
                        .block();
        access.assignRoleByCode(student.id(), Roles.STUDENT).block();
        String token = "Bearer " + TestAuth.login(client, username, PASSWORD);

        client.get()
                .uri(uri -> uri.path(DATA_SCOPES).queryParam("roleId", customRoleId).build())
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isForbidden();

        client.put()
                .uri(DATA_SCOPES)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(customRoleId, DATASOURCE, "score", "DENY", null, null))
                .exchange()
                .expectStatus()
                .isForbidden();

        client.delete()
                .uri(DATA_SCOPES + "/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isForbidden();

        assertThat(listRules()).isEmpty();
        assertThat(auditRows("data_scope.upsert", null, "SUCCESS")).isZero();
        assertThat(auditRows("data_scope.delete", null, "SUCCESS")).isZero();
    }

    @Test
    void deleteRemovesTheRuleAndAnUnknownIdIsNotFound() {
        String id = String.valueOf(upsertRule("score", "FILTER", "student_id", "studentId").get("id"));

        client.delete()
                .uri(DATA_SCOPES + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(listRules()).isEmpty();

        client.delete()
                .uri(DATA_SCOPES + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("数据范围不存在");

        assertThat(auditRows("data_scope.delete", null, "SUCCESS")).isEqualTo(1);
        assertThat(auditRows("data_scope.delete", null, "DENIED")).isEqualTo(1);
    }

    @Test
    void eachMutationWritesOneSuccessRowWithBeforeAndAfter() {
        String id = String.valueOf(upsertRule("score", "FILTER", "student_id", "studentId").get("id"));
        upsertRule("score", "DENY", null, null);
        client.delete()
                .uri(DATA_SCOPES + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(auditRows("data_scope.upsert", id, "SUCCESS")).isEqualTo(2);
        assertThat(auditRows("data_scope.delete", id, "SUCCESS")).isEqualTo(1);

        String filterJson =
                "{\"id\":\"" + id + "\",\"roleId\":\"" + customRoleId + "\",\"datasourceId\":\""
                        + DATASOURCE + "\",\"tableName\":\"score\",\"policy\":\"FILTER\","
                        + "\"columnName\":\"student_id\",\"principalAttribute\":\"studentId\"}";
        String denyJson =
                "{\"id\":\"" + id + "\",\"roleId\":\"" + customRoleId + "\",\"datasourceId\":\""
                        + DATASOURCE + "\",\"tableName\":\"score\",\"policy\":\"DENY\","
                        + "\"columnName\":null,\"principalAttribute\":null}";

        // The first upsert had no before state; the second sees the FILTER it replaced.
        assertThat(transitions("data_scope.upsert", id, "SUCCESS"))
                .containsExactlyInAnyOrder("null -> " + filterJson, filterJson + " -> " + denyJson);

        assertThat(transitions("data_scope.delete", id, "SUCCESS"))
                .containsExactly(denyJson + " -> null");
    }

    @Test
    void aRejectedValidationWritesExactlyOneDeniedRow() {
        error("no_such_table", "ALL", null, null, 422);

        assertThat(auditRows("data_scope.upsert", null, "DENIED")).isEqualTo(1);
        assertThat(auditDistinct("data_scope.upsert", "DENIED", "entity_type"))
                .containsExactly("DATA_SCOPE");
        assertThat(auditDistinct("data_scope.upsert", "DENIED", "reason"))
                .containsExactly("表不存在：no_such_table");
        assertThat(auditTransitions("data_scope.upsert", "DENIED"))
                .containsExactly(
                        "null -> {\"id\":null,\"roleId\":\"" + customRoleId + "\",\"datasourceId\":\""
                                + DATASOURCE + "\",\"tableName\":\"no_such_table\","
                                + "\"policy\":\"ALL\",\"columnName\":null,"
                                + "\"principalAttribute\":null}");

        // A shape rejection (400) is not audited: it never reached an entity.
        expectedError("score", "FILTER", null, null, 400);
        assertThat(auditRows("data_scope.upsert", null, "DENIED")).isEqualTo(1);
    }

    @Test
    void aStoredFilterAppliesToTheNextRequest() {
        AppUser user =
                users.insert(unique("u"), passwordEncoder.encode(PASSWORD), "Tester", Roles.STUDENT)
                        .block();
        assign(user.id(), customRoleId);

        assertThat(engine.decide(resolve(user.id()), DATASOURCE, "score").denied())
                .as("no rule yet: the table is denied by default")
                .isTrue();

        upsertRule("score", "FILTER", "student_id", "studentId");
        upsertRule("class", "ALL", null, null);

        EffectiveAccess after = resolve(user.id());
        assertThat(after.roles()).extracting(role -> role.id()).contains(customRoleId);
        assertThat(after.dataScopes())
                .extracting(DataScopeRule::tableName)
                .containsExactlyInAnyOrder("score", "class");
        DataScopeRule score =
                after.dataScopes().stream()
                        .filter(rule -> "score".equals(rule.tableName()))
                        .findFirst()
                        .orElseThrow();
        assertThat(score.policy()).isEqualTo(DataScopePolicy.FILTER);
        assertThat(score.columnName()).isEqualTo("student_id");
        assertThat(score.principalAttribute()).isEqualTo(PrincipalAttribute.STUDENT_ID.code());

        PolicyDecision decision = engine.decide(after, DATASOURCE, "score");
        assertThat(decision.filtered()).isTrue();
        assertThat(decision.predicates()).hasSize(1);
        assertThat(decision.predicates().get(0).column()).isEqualTo("student_id");
        assertThat(decision.predicates().get(0).attribute())
                .isEqualTo(PrincipalAttribute.STUDENT_ID);

        assertThat(engine.decide(after, DATASOURCE, "class").unrestricted())
                .as("the ALL rule is unrestricted")
                .isTrue();

        // Deleting the rule reverts the next request to deny, without a new token.
        client.delete()
                .uri(DATA_SCOPES + "/" + score.id())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(engine.decide(resolve(user.id()), DATASOURCE, "score").denied()).isTrue();
    }

    // --- helpers ----------------------------------------------------------------------------

    private String bearer() {
        return "Bearer " + adminToken;
    }

    private static Map<String, Object> body(
            String roleId,
            String datasourceId,
            String tableName,
            String policy,
            String columnName,
            String principalAttribute) {
        // A HashMap (not Map.of) so a null field is sent explicitly and the shape is visible.
        Map<String, Object> body = new HashMap<>();
        body.put("roleId", roleId);
        body.put("datasourceId", datasourceId);
        body.put("tableName", tableName);
        body.put("policy", policy);
        body.put("columnName", columnName);
        body.put("principalAttribute", principalAttribute);
        return body;
    }

    /** Upserts a rule for this test's role against the campus datasource. */
    private Map<String, Object> upsertRule(
            String tableName, String policy, String columnName, String principalAttribute) {
        Map<String, Object> response =
                client.put()
                        .uri(DATA_SCOPES)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(
                                body(
                                        customRoleId,
                                        DATASOURCE,
                                        tableName,
                                        policy,
                                        columnName,
                                        principalAttribute))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .returnResult()
                        .getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private void expectedError(
            String tableName,
            String policy,
            String columnName,
            String principalAttribute,
            int status) {
        error(tableName, policy, columnName, principalAttribute, status);
    }

    private String error(
            String tableName,
            String policy,
            String columnName,
            String principalAttribute,
            int status) {
        Map<String, Object> response =
                client.put()
                        .uri(DATA_SCOPES)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(
                                body(
                                        customRoleId,
                                        DATASOURCE,
                                        tableName,
                                        policy,
                                        columnName,
                                        principalAttribute))
                        .exchange()
                        .expectStatus()
                        .isEqualTo(status)
                        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .returnResult()
                        .getResponseBody();
        assertThat(response).isNotNull();
        assertThat(response.get("status")).isEqualTo(status);
        return String.valueOf(response.get("message"));
    }

    private List<Map<String, Object>> listRules() {
        return listRules(null);
    }

    private List<Map<String, Object>> listRules(String datasourceId) {
        List<Map<String, Object>> rules =
                client.get()
                        .uri(
                                uri -> {
                                    uri.path(DATA_SCOPES).queryParam("roleId", customRoleId);
                                    if (datasourceId != null) {
                                        uri.queryParam("datasourceId", datasourceId);
                                    }
                                    return uri.build();
                                })
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBodyList(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .returnResult()
                        .getResponseBody();
        assertThat(rules).isNotNull();
        return rules;
    }

    private EffectiveAccess resolve(String userId) {
        Integer tokenVersion =
                db.sql("SELECT token_version FROM app_user WHERE id = :id")
                        .bind("id", userId)
                        .map((row, meta) -> {
                            Integer version = row.get("token_version", Integer.class);
                            return version == null ? 0 : version;
                        })
                        .one()
                        .block();
        return resolver.resolve(userId, tokenVersion).block();
    }

    /** Creates a fresh, non built-in role and returns its id. */
    private String createRole() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String roleId = "test-role-scope-" + suffix;
        long now = System.currentTimeMillis();
        db.sql(
                        "INSERT INTO access_role (id, code, name, description, parent_id, built_in,"
                                + " enabled, created_at, updated_at)"
                                + " VALUES (:id, :code, :name, NULL, NULL, FALSE, TRUE, :now, :now)")
                .bind("id", roleId)
                .bind("code", "scope-" + suffix)
                .bind("name", "Scope " + suffix)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .block();
        return roleId;
    }

    private void assign(String userId, String roleId) {
        db.sql("INSERT INTO access_user_role (user_id, role_id, created_at) VALUES (:user, :role, :now)")
                .bind("user", userId)
                .bind("role", roleId)
                .bind("now", System.currentTimeMillis())
                .fetch()
                .rowsUpdated()
                .block();
    }

    /**
     * Counts audit rows for this test's admin actor and the given action/result.
     *
     * @param entityId the entity id to filter by, or {@code null} to apply <em>no</em> id filter
     */
    private long auditRows(String action, String entityId, String result) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT COUNT(*) AS n FROM access_audit"
                                + " WHERE actor_id = :actorId AND action = :action AND result = :result");
        if (entityId != null) {
            sql.append(" AND entity_id = :entityId");
        }
        DatabaseClient.GenericExecuteSpec spec =
                db.sql(sql.toString())
                        .bind("actorId", adminId)
                        .bind("action", action)
                        .bind("result", result);
        if (entityId != null) {
            spec = spec.bind("entityId", entityId);
        }
        Long count = spec.map((row, meta) -> row.get("n", Long.class)).one().block();
        return count == null ? 0L : count;
    }

    private List<String> auditDistinct(String action, String result, String column) {
        return db.sql(
                        "SELECT DISTINCT " + column + " AS audit_value FROM access_audit"
                                + " WHERE actor_id = :actorId AND action = :action AND result = :result")
                .bind("actorId", adminId)
                .bind("action", action)
                .bind("result", result)
                .map((row, meta) -> row.get("audit_value", String.class))
                .all()
                .collectList()
                .block();
    }

    private List<String> transitions(String action, String entityId, String result) {
        return auditTransitions(action, result, entityId);
    }

    private List<String> auditTransitions(String action, String result) {
        return auditTransitions(action, result, null);
    }

    private List<String> auditTransitions(String action, String result, String entityId) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT DISTINCT before_json, after_json FROM access_audit"
                                + " WHERE actor_id = :actorId AND action = :action AND result = :result");
        if (entityId != null) {
            sql.append(" AND entity_id = :entityId");
        }
        DatabaseClient.GenericExecuteSpec spec =
                db.sql(sql.toString())
                        .bind("actorId", adminId)
                        .bind("action", action)
                        .bind("result", result);
        if (entityId != null) {
            spec = spec.bind("entityId", entityId);
        }
        return spec.map((row, meta) -> row.get("before_json", String.class)
                        + " -> "
                        + row.get("after_json", String.class))
                .all()
                .collectList()
                .block();
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
