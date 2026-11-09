package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.access.AccessContextResolver;
import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.access.EffectiveAccess;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.user.AppUser;
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
 * The role → datasource matrix: read the full matrix, read and replace one role's grant set, with
 * audit, next-request effect and legacy compatibility.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessControlDatasourceMatrixTest {

    private static final String PASSWORD = "secret123";

    private static final String BASE = "/api/admin/access-control";

    private static final String ROLES = BASE + "/roles";

    private static final String DATASOURCE_ACCESS = BASE + "/datasource-access";

    private static final String DATASOURCE = "campus";

    /** A second configured datasource (dash in the id, same demo schema). */
    private static final String SECOND_DATASOURCE = "erp-prod";

    private static final String LEGACY = "/api/admin/datasource-access";

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void matrixListsBuiltInRolesWithTheirSeededGrantsAndTheRegisteredDatasources() {
        Map<String, Object> matrix = matrix();
        List<Map<String, Object>> roles = (List) matrix.get("roles");

        // Ordered by code; every built-in role appears, each carrying its stable identity fields.
        List<String> codes = roles.stream().map(role -> String.valueOf(role.get("code"))).toList();
        assertThat(codes).isSorted();
        assertThat(codes).contains("admin", "counselor", "student");

        assertThat(roleByCode(matrix, "student").get("builtIn")).isEqualTo(true);
        assertThat(roleByCode(matrix, "student").get("enabled")).isEqualTo(true);
        assertThat((List<String>) roleByCode(matrix, "student").get("datasourceIds"))
                .contains(DATASOURCE);
        assertThat((List<String>) roleByCode(matrix, "counselor").get("datasourceIds"))
                .contains(DATASOURCE);
        // Admins bypass datasource grants by definition; no rows are synthesised for them.
        assertThat((List<String>) roleByCode(matrix, "admin").get("datasourceIds")).isEmpty();

        // The registered datasources, with the default flagged and the rest not.
        List<Map<String, Object>> datasources = (List) matrix.get("datasources");
        assertThat(datasources)
                .extracting(datasource -> String.valueOf(datasource.get("id")))
                .containsExactly(DATASOURCE, SECOND_DATASOURCE);
        assertThat(datasources)
                .filteredOn(datasource -> DATASOURCE.equals(datasource.get("id")))
                .first()
                .satisfies(datasource -> assertThat(datasource.get("default")).isEqualTo(true));
        assertThat(datasources)
                .filteredOn(datasource -> SECOND_DATASOURCE.equals(datasource.get("id")))
                .first()
                .satisfies(datasource -> assertThat(datasource.get("default")).isEqualTo(false));
    }

    @Test
    void aFreshCustomRoleStartsEmptyAndPutReplacesRatherThanUnions() {
        assertThat(datasourceIdsInMatrix(customRoleId)).isEmpty();
        assertThat(getDatasources()).isEmpty();

        putDatasources(List.of(DATASOURCE, SECOND_DATASOURCE))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roleId")
                .isEqualTo(customRoleId)
                .jsonPath("$.datasourceIds.length()")
                .isEqualTo(2)
                .jsonPath("$.datasourceIds[0]")
                .isEqualTo(DATASOURCE);

        assertThat(getDatasources()).containsExactly(DATASOURCE, SECOND_DATASOURCE);
        assertThat(datasourceIdsInMatrix(customRoleId))
                .containsExactly(DATASOURCE, SECOND_DATASOURCE);

        // Re-PUT replaces (does not union): only the requested ids remain.
        putDatasources(List.of(DATASOURCE))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.datasourceIds.length()")
                .isEqualTo(1);
        assertThat(getDatasources()).containsExactly(DATASOURCE);

        // An empty array clears every grant.
        putDatasources(List.of())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.datasourceIds.length()")
                .isEqualTo(0);
        assertThat(getDatasources()).isEmpty();
        assertThat(datasourceIdsInMatrix(customRoleId)).isEmpty();
    }

    @Test
    void duplicateIdsAreDeduplicated() {
        putDatasources(List.of(DATASOURCE, DATASOURCE))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.datasourceIds.length()")
                .isEqualTo(1);

        assertThat(getDatasources()).containsExactly(DATASOURCE);
    }

    @Test
    void unknownRoleAndDatasourceAreNotFound() {
        String unknownRole = UUID.randomUUID().toString();

        client.put()
                .uri(ROLES + "/" + unknownRole + "/datasources")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("datasourceIds", List.of(DATASOURCE)))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("角色不存在");

        client.get()
                .uri(ROLES + "/" + unknownRole + "/datasources")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("角色不存在");

        putDatasources(List.of("no-such-ds"))
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("数据源不存在：no-such-ds");
    }

    @Test
    void aMissingBodyDoesNotClearTheSet() {
        putDatasources(List.of(DATASOURCE)).expectStatus().isOk();

        client.put()
                .uri(ROLES + "/" + customRoleId + "/datasources")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("datasourceIds 不能为空");

        client.put()
                .uri(ROLES + "/" + customRoleId + "/datasources")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"datasourceIds\": null}")
                .exchange()
                .expectStatus()
                .isBadRequest();

        assertThat(getDatasources())
                .as("omitting the body must not revoke every grant")
                .containsExactly(DATASOURCE);
        assertThat(auditRows(customRoleId, "SUCCESS")).isEqualTo(1);
    }

    @Test
    void aStudentCannotReadOrReplaceTheMatrix() {
        String username = unique("student");
        AppUser student =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Student", Roles.STUDENT)
                        .block();
        access.assignRoleByCode(student.id(), Roles.STUDENT).block();
        String token = "Bearer " + TestAuth.login(client, username, PASSWORD);

        client.get()
                .uri(DATASOURCE_ACCESS)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isForbidden();

        client.get()
                .uri(ROLES + "/" + customRoleId + "/datasources")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isForbidden();

        client.put()
                .uri(ROLES + "/" + customRoleId + "/datasources")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("datasourceIds", List.of(DATASOURCE)))
                .exchange()
                .expectStatus()
                .isForbidden();

        assertThat(getDatasources()).isEmpty();
        assertThat(auditRows(customRoleId, "SUCCESS")).isZero();
    }

    @Test
    void eachReplaceWritesOneSuccessRowWithSortedBeforeAndAfter() {
        putDatasources(List.of(SECOND_DATASOURCE, DATASOURCE)).expectStatus().isOk();
        putDatasources(List.of(DATASOURCE)).expectStatus().isOk();

        assertThat(auditRows(customRoleId, "SUCCESS")).isEqualTo(2);
        assertThat(auditDistinct(customRoleId, "SUCCESS", "action"))
                .containsExactly("role.datasources");
        assertThat(auditDistinct(customRoleId, "SUCCESS", "entity_type")).containsExactly("ROLE");
        assertThat(auditTransitions(customRoleId, "SUCCESS"))
                .containsExactlyInAnyOrder(
                        "[] -> [\"" + DATASOURCE + "\",\"" + SECOND_DATASOURCE + "\"]",
                        "[\"" + DATASOURCE + "\",\"" + SECOND_DATASOURCE + "\"]"
                                + " -> [\"" + DATASOURCE + "\"]");
    }

    @Test
    void anUnknownDatasourceIsNotFoundAndAudited() {
        putDatasources(List.of(DATASOURCE)).expectStatus().isOk();
        long successBefore = auditRows(customRoleId, "SUCCESS");

        putDatasources(List.of(DATASOURCE, "no-such-ds"))
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("数据源不存在：no-such-ds");

        assertThat(getDatasources())
                .as("a rejected request does not change the set")
                .containsExactly(DATASOURCE);
        assertThat(auditRows(customRoleId, "DENIED")).isEqualTo(1);
        assertThat(auditRows(customRoleId, "SUCCESS")).isEqualTo(successBefore);
        assertThat(auditDistinct(customRoleId, "DENIED", "reason"))
                .containsExactly("数据源不存在：no-such-ds");
        // The DENIED row's before state is the role's real current set, not [].
        assertThat(auditTransitions(customRoleId, "DENIED"))
                .containsExactly(
                        "[\"" + DATASOURCE + "\"]"
                                + " -> [\"" + DATASOURCE + "\",\"no-such-ds\"]");
    }

    @Test
    void aGrantedDatasourceAppliesToTheNextRequest() {
        AppUser user =
                users.insert(unique("u"), passwordEncoder.encode(PASSWORD), "Tester", Roles.STUDENT)
                        .block();
        assign(user.id(), customRoleId);
        assertThat(resolve(user.id()).datasourceIds()).doesNotContain(DATASOURCE);

        putDatasources(List.of(DATASOURCE)).expectStatus().isOk();

        EffectiveAccess after = resolve(user.id());
        assertThat(after.roles()).extracting(role -> role.id()).contains(customRoleId);
        assertThat(after.datasourceIds())
                .as("the edited set applies on the next resolution, without a new token")
                .contains(DATASOURCE);

        // Revoking the grant removes it from the very next resolution.
        putDatasources(List.of()).expectStatus().isOk();
        assertThat(resolve(user.id()).datasourceIds()).doesNotContain(DATASOURCE);
    }

    @Test
    void legacyDatasourceAccessEndpointKeepsTheSameUnderlyingGrant() {
        String studentRoleId = String.valueOf(roleByCode(matrix(), "student").get("id"));

        // The legacy endpoint grants by role code and dual-writes the enforcement row; the new
        // role-record matrix must reflect the same underlying grant.
        client.put()
                .uri(LEGACY + "/student/" + SECOND_DATASOURCE)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(datasourceIdsInMatrix(studentRoleId))
                .contains(DATASOURCE, SECOND_DATASOURCE);

        client.delete()
                .uri(LEGACY + "/student/" + SECOND_DATASOURCE)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(datasourceIdsInMatrix(studentRoleId))
                .contains(DATASOURCE)
                .doesNotContain(SECOND_DATASOURCE);
    }

    // --- helpers ----------------------------------------------------------------------------

    private String bearer() {
        return "Bearer " + adminToken;
    }

    private WebTestClient.ResponseSpec putDatasources(List<String> ids) {
        return client.put()
                .uri(ROLES + "/" + customRoleId + "/datasources")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("datasourceIds", ids))
                .exchange();
    }

    private Map<String, Object> matrix() {
        Map<String, Object> body =
                client.get()
                        .uri(DATASOURCE_ACCESS)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .returnResult()
                        .getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> roleByCode(Map<String, Object> matrix, String code) {
        List<Map<String, Object>> roles = (List<Map<String, Object>>) matrix.get("roles");
        return roles.stream()
                .filter(role -> code.equals(String.valueOf(role.get("code"))))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<String> datasourceIdsInMatrix(String roleId) {
        List<Map<String, Object>> roles = (List<Map<String, Object>>) matrix().get("roles");
        return roles.stream()
                .filter(role -> roleId.equals(String.valueOf(role.get("id"))))
                .map(role -> (List<String>) role.getOrDefault("datasourceIds", List.of()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<String> getDatasources() {
        Map<String, Object> body =
                client.get()
                        .uri(ROLES + "/" + customRoleId + "/datasources")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .returnResult()
                        .getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("roleId")).isEqualTo(customRoleId);
        return (List<String>) body.getOrDefault("datasourceIds", List.of());
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
        String roleId = "test-role-ds-" + suffix;
        long now = System.currentTimeMillis();
        db.sql(
                        "INSERT INTO access_role (id, code, name, description, parent_id, built_in,"
                                + " enabled, created_at, updated_at)"
                                + " VALUES (:id, :code, :name, NULL, NULL, FALSE, TRUE, :now, :now)")
                .bind("id", roleId)
                .bind("code", "ds-" + suffix)
                .bind("name", "DS " + suffix)
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

    private long auditRows(String entityId, String result) {
        Long count =
                db.sql(
                                "SELECT COUNT(*) AS n FROM access_audit"
                                        + " WHERE actor_id = :actorId AND action = :action"
                                        + " AND entity_id = :entityId AND result = :result")
                        .bind("actorId", adminId)
                        .bind("action", "role.datasources")
                        .bind("entityId", entityId)
                        .bind("result", result)
                        .map((row, meta) -> row.get("n", Long.class))
                        .one()
                        .block();
        return count == null ? 0L : count;
    }

    /**
     * The distinct values of one audit column, for this test's actor and the given result.
     *
     * <p>Asserted on content rather than on row order: two rows written in the same millisecond have
     * no stable order (the id is a random UUID).
     */
    private List<String> auditDistinct(String entityId, String result, String column) {
        return db.sql(
                        "SELECT DISTINCT " + column + " AS audit_value FROM access_audit"
                                + " WHERE actor_id = :actorId AND action = :action"
                                + " AND entity_id = :entityId AND result = :result")
                .bind("actorId", adminId)
                .bind("action", "role.datasources")
                .bind("entityId", entityId)
                .bind("result", result)
                .map((row, meta) -> row.get("audit_value", String.class))
                .all()
                .collectList()
                .block();
    }

    /** The distinct before -> after transitions recorded for the role. */
    private List<String> auditTransitions(String entityId, String result) {
        return db.sql(
                        "SELECT DISTINCT before_json, after_json FROM access_audit"
                                + " WHERE actor_id = :actorId AND action = :action"
                                + " AND entity_id = :entityId AND result = :result")
                .bind("actorId", adminId)
                .bind("action", "role.datasources")
                .bind("entityId", entityId)
                .bind("result", result)
                .map((row, meta) -> row.get("before_json", String.class)
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
