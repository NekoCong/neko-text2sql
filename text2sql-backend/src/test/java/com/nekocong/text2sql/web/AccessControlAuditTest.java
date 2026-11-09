package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.access.Permissions;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The read-only access-control audit API: paginated, filtered listing of the {@code access_audit}
 * trail, gated by {@code audit.read}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessControlAuditTest {

    private static final String PASSWORD = "secret123";

    private static final String BASE = "/api/admin/access-control";

    private static final String AUDIT = BASE + "/audit";

    private static final String ROLES = BASE + "/roles";

    private static final String USERS = BASE + "/users";

    private static final String DATA_SCOPES = BASE + "/data-scopes";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccessControlRepository access;

    @Autowired
    private DatabaseClient db;

    private WebTestClient client;
    private String adminToken;
    private String adminId;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        String username = unique("admin");
        AppUser admin =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Admin", Roles.ADMIN).block();
        access.assignRoleByCode(admin.id(), Roles.ADMIN).block();
        adminId = admin.id();
        adminToken = TestAuth.login(client, username, PASSWORD);
    }

    @Test
    void listsMutationsWithActorActionEntityResultParsedBeforeAfterAndTimestamp() {
        String studentRoleId = roleIdByCode(Roles.STUDENT);

        String roleId = createRoleViaApi(unique("audr"), "审计角色");
        String userId = createUserViaApi(unique("audu"), List.of(studentRoleId));

        // A role permission replace (role.permissions), a user role replace (user.roles) and a
        // data-scope upsert (data_scope.upsert) each write one SUCCESS row.
        setPermissions(roleId, List.of(Permissions.CHAT_USE)).expectStatus().isOk();
        setRoles(userId, List.of(studentRoleId)).expectStatus().isOk();
        upsertDataScope(roleId, "score", "ALL").expectStatus().isOk();

        // role.create.
        Map<String, Object> create = auditItems(Map.of("actorId", adminId, "action", "role.create")).get(0);
        assertThat(create.get("actorId")).isEqualTo(adminId);
        assertThat(create.get("entityType")).isEqualTo("ROLE");
        assertThat(create.get("entityId")).isEqualTo(roleId);
        assertThat(create.get("result")).isEqualTo("SUCCESS");
        assertThat(((Number) create.get("createdAt")).longValue()).isPositive();
        assertThat(create.get("before")).isNull();
        assertThat(create.get("after")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> createAfter = (Map<String, Object>) create.get("after");
        assertThat(createAfter).containsKey("code");

        // user.create.
        Map<String, Object> userCreate = auditItems(Map.of("actorId", adminId, "action", "user.create")).get(0);
        assertThat(userCreate.get("entityType")).isEqualTo("USER");
        assertThat(userCreate.get("entityId")).isEqualTo(userId);
        assertThat(userCreate.get("result")).isEqualTo("SUCCESS");
        assertThat(userCreate.get("after")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) userCreate.get("after")).get("username")).isNotNull();

        // user.roles.
        Map<String, Object> userRoles = auditItems(Map.of("actorId", adminId, "action", "user.roles")).get(0);
        assertThat(userRoles.get("entityType")).isEqualTo("USER");
        assertThat(userRoles.get("entityId")).isEqualTo(userId);
        assertThat(userRoles.get("result")).isEqualTo("SUCCESS");
        assertThat(userRoles.get("after")).isInstanceOf(Map.class);

        // data_scope.upsert (after is the parsed rule object, not a string).
        Map<String, Object> scope = auditItems(Map.of("actorId", adminId, "action", "data_scope.upsert")).get(0);
        assertThat(scope.get("entityType")).isEqualTo("DATA_SCOPE");
        assertThat(scope.get("result")).isEqualTo("SUCCESS");
        assertThat(scope.get("after")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) scope.get("after")).get("tableName")).isEqualTo("score");

        // role.permissions (after is the parsed key array, not a string).
        Map<String, Object> perms = auditItems(Map.of("actorId", adminId, "action", "role.permissions")).get(0);
        assertThat(perms.get("entityType")).isEqualTo("ROLE");
        assertThat(perms.get("entityId")).isEqualTo(roleId);
        assertThat(perms.get("after")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> afterKeys = (List<String>) perms.get("after");
        assertThat(afterKeys).contains(Permissions.CHAT_USE);
    }

    @Test
    void deniedRowsAreVisibleWithTheirReason() {
        String code = unique("audd");
        String roleId = createRoleViaApi(code, "拒绝审计");

        // Duplicate code -> role.create DENIED.
        client.post()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", code, "name", "重复"))
                .exchange()
                .expectStatus()
                .isEqualTo(409);

        // Unknown role -> role.update DENIED.
        put(ROLES + "/" + UUID.randomUUID(), Map.of("name", "不存在")).expectStatus().isNotFound();

        // Unknown permission key -> role.permissions DENIED.
        setPermissions(roleId, List.of("no.such.key")).expectStatus().isBadRequest();

        // Unknown user -> user.disable DENIED.
        client.put()
                .uri(USERS + "/" + UUID.randomUUID() + "/status")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus()
                .isNotFound();

        // Unknown data scope -> data_scope.delete DENIED.
        client.delete()
                .uri(DATA_SCOPES + "/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isNotFound();

        List<Map<String, Object>> denied = auditItems(Map.of("actorId", adminId, "result", "DENIED"));
        assertThat(denied).isNotEmpty();
        assertThat(denied)
                .extracting(row -> String.valueOf(row.get("action")))
                .contains("role.create", "role.update", "role.permissions", "user.disable",
                        "data_scope.delete");
        assertThat(denied)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("result")).isEqualTo("DENIED");
                            assertThat(String.valueOf(row.get("reason"))).isNotBlank();
                        });
    }

    @Test
    void eachFilterNarrowsCorrectly() {
        String otherActor = UUID.randomUUID().toString();
        insertAudit("f1", adminId, "role.create", "ROLE", "role-1", null, "{}", "SUCCESS", null, 1000L);
        insertAudit("f2", adminId, "user.create", "USER", "user-1", null, "{}", "SUCCESS", null, 2000L);
        insertAudit("f3", otherActor, "user.create", "USER", "user-2", null, "{}", "DENIED", "原因", 3000L);
        insertAudit("f4", adminId, "role.update", "ROLE", "role-2", "{}", "{}", "SUCCESS", null, 4000L);

        assertThat(auditItems(Map.of("actorId", adminId))).hasSize(3);
        assertThat(auditItems(Map.of("actorId", otherActor))).hasSize(1);

        assertThat(auditItems(Map.of("actorId", adminId, "action", "user.create"))).hasSize(1);
        assertThat(auditItems(Map.of("actorId", adminId, "entityType", "USER"))).hasSize(1);
        assertThat(auditItems(Map.of("actorId", adminId, "entityId", "role-1"))).hasSize(1);
        assertThat(auditItems(Map.of("actorId", adminId, "result", "DENIED"))).isEmpty();

        // from inclusive, to exclusive.
        assertThat(auditItems(Map.of("actorId", adminId, "from", "2000", "to", "4000")))
                .extracting(row -> row.get("id"))
                .containsExactly("f2");
        assertThat(auditItems(Map.of("actorId", adminId, "from", "4000")))
                .extracting(row -> row.get("id"))
                .containsExactly("f4");
        assertThat(auditItems(Map.of("actorId", adminId, "to", "2000")))
                .extracting(row -> row.get("id"))
                .containsExactly("f1");

        // An unknown action/entityType simply matches nothing (exact match), never an error.
        assertThat(auditItems(Map.of("actorId", adminId, "action", "role.nope"))).isEmpty();
        assertThat(auditItems(Map.of("actorId", adminId, "entityType", "NOPE"))).isEmpty();
    }

    @Test
    void invalidResultTimeRangeAndPaginationAreRejected() {
        expectBadRequest(Map.of("result", "MAYBE"), "结果过滤值无效");
        expectBadRequest(Map.of("from", "2000", "to", "1000"), "时间范围无效");
        expectBadRequest(Map.of("from", "not-a-number"), "时间范围无效");
        expectBadRequest(Map.of("page", "-1"), "分页参数无效");
        expectBadRequest(Map.of("size", "0"), "分页参数无效");
    }

    @Test
    void paginationIsStableNewestFirstWithNoOverlap() {
        String actor = UUID.randomUUID().toString();
        for (int i = 1; i <= 10; i++) {
            insertAudit(
                    "pg-" + String.format("%02d", i),
                    actor,
                    "role.create",
                    "ROLE",
                    "role-" + i,
                    null,
                    "{}",
                    "SUCCESS",
                    null,
                    i * 1000L);
        }

        Map<String, Object> page0 = auditPage(Map.of("actorId", actor, "page", "0", "size", "3"));
        assertThat(((Number) page0.get("total")).intValue()).isEqualTo(10);
        assertThat(((Number) page0.get("page")).intValue()).isZero();
        assertThat(((Number) page0.get("size")).intValue()).isEqualTo(3);
        List<String> first = itemIds(page0);
        assertThat(first).containsExactly("pg-10", "pg-09", "pg-08");

        List<String> second = itemIds(auditPage(Map.of("actorId", actor, "page", "1", "size", "3")));
        assertThat(second).containsExactly("pg-07", "pg-06", "pg-05");
        assertThat(first).doesNotContainAnyElementsOf(second);

        List<String> last = itemIds(auditPage(Map.of("actorId", actor, "page", "3", "size", "3")));
        assertThat(last).containsExactly("pg-01");

        // Same-millisecond ties are broken deterministically by id DESC.
        insertAudit("tie-1", actor, "role.create", "ROLE", "role-tie", null, "{}", "SUCCESS", null, 9999L);
        insertAudit("tie-2", actor, "role.create", "ROLE", "role-tie", null, "{}", "SUCCESS", null, 9999L);
        assertThat(itemIds(auditPage(Map.of("actorId", actor, "entityId", "role-tie", "size", "2"))))
                .containsExactly("tie-2", "tie-1");

        // The size is capped at 200, not rejected.
        Map<String, Object> capped = auditPage(Map.of("actorId", actor, "size", "500"));
        assertThat(((Number) capped.get("size")).intValue()).isEqualTo(200);
    }

    @Test
    void auditReadIsRequiredAndNoOtherPermissionSuffices() {
        expectForbidden(newAccount(Permissions.ACCESS_MANAGE));
        expectForbidden(newAccount(Permissions.USER_READ));
        expectForbidden(newAccount(Permissions.USER_MANAGE));

        Account auditor = newAccount(Permissions.AUDIT_READ);
        client.get()
                .uri(AUDIT)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(auditor))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void readOutputContainsNoSecretMaterial() {
        String username = unique("secret");
        String userId = createUserViaApi(username, List.of(roleIdByCode(Roles.STUDENT)));
        // A password reset exercises the one audit payload that mentions a password field (the
        // constant "changed" sentinel, never the actual password).
        client.post()
                .uri(USERS + "/" + userId + "/reset-password")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", "newsecret9"))
                .exchange()
                .expectStatus()
                .isNoContent();

        String body = auditBody(Map.of("actorId", adminId));
        assertThat(body)
                .doesNotContain(PASSWORD)
                .doesNotContain("newsecret9")
                .doesNotContain("password_hash")
                .doesNotContain("passwordHash")
                .doesNotContain(adminToken);
    }

    // --- helpers ----------------------------------------------------------------------------

    private String bearer() {
        return "Bearer " + adminToken;
    }

    private String bearerFor(Account account) {
        return "Bearer " + TestAuth.login(client, account.username(), PASSWORD);
    }

    private void expectForbidden(Account account) {
        client.get()
                .uri(AUDIT)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(account))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    private Account newAccount(String... keys) {
        String roleId = createRoleGranting(keys);
        String username = unique("op");
        String id = createUser(username, roleId);
        return new Account(id, username);
    }

    private WebTestClient.ResponseSpec put(String uri, Map<String, Object> body) {
        return client.put()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private WebTestClient.ResponseSpec setPermissions(String roleId, List<String> keys) {
        return client.put()
                .uri(ROLES + "/" + roleId + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("permissions", keys))
                .exchange();
    }

    private WebTestClient.ResponseSpec setRoles(String userId, List<String> roleIds) {
        return client.put()
                .uri(USERS + "/" + userId + "/roles")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("roleIds", roleIds))
                .exchange();
    }

    private WebTestClient.ResponseSpec upsertDataScope(String roleId, String table, String policy) {
        return client.put()
                .uri(DATA_SCOPES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "roleId", roleId,
                                "datasourceId", "campus",
                                "tableName", table,
                                "policy", policy))
                .exchange();
    }

    /** Creates a role through the API and returns its id. */
    private String createRoleViaApi(String code, String name) {
        Map<String, Object> created =
                client.post()
                        .uri(ROLES)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("code", code, "name", name))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertThat(created).isNotNull();
        return String.valueOf(created.get("id"));
    }

    /** Creates a user through the API and returns its id. */
    private String createUserViaApi(String username, List<String> roleIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("roleIds", roleIds);
        Map<String, Object> created =
                client.post()
                        .uri(USERS)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertThat(created).isNotNull();
        return String.valueOf(created.get("id"));
    }

    private String roleIdByCode(String code) {
        String id =
                db.sql("SELECT id FROM access_role WHERE code = :code")
                        .bind("code", code)
                        .map((row, meta) -> row.get("id", String.class))
                        .one()
                        .block();
        assertThat(id).as("role " + code).isNotNull();
        return id;
    }

    /** Creates a fresh, non built-in role granting the given permission keys, and returns its id. */
    private String createRoleGranting(String... keys) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String roleId = "test-role-audit-" + suffix;
        long now = System.currentTimeMillis();
        db.sql(
                        "INSERT INTO access_role (id, code, name, description, parent_id, built_in,"
                                + " enabled, created_at, updated_at)"
                                + " VALUES (:id, :code, :name, NULL, NULL, FALSE, TRUE, :now, :now)")
                .bind("id", roleId)
                .bind("code", "audit" + suffix)
                .bind("name", "Audit " + suffix)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .block();
        for (String key : keys) {
            grantPermission(roleId, key);
        }
        return roleId;
    }

    private void grantPermission(String roleId, String key) {
        String permissionId =
                db.sql("SELECT id FROM access_permission WHERE permission_key = :key")
                        .bind("key", key)
                        .map((row, meta) -> row.get("id", String.class))
                        .one()
                        .block();
        assertThat(permissionId).as("permission " + key).isNotNull();
        db.sql("INSERT INTO access_role_permission (role_id, permission_id) VALUES (:roleId, :permissionId)")
                .bind("roleId", roleId)
                .bind("permissionId", permissionId)
                .fetch()
                .rowsUpdated()
                .block();
    }

    /** Creates a user (under the access-control tables) with the given role and returns its id. */
    private String createUser(String username, String roleId) {
        AppUser user = users.insert(username, passwordEncoder.encode(PASSWORD), "Tester", Roles.STUDENT).block();
        db.sql("INSERT INTO access_user_role (user_id, role_id, created_at) VALUES (:u, :r, :now)")
                .bind("u", user.id())
                .bind("r", roleId)
                .bind("now", System.currentTimeMillis())
                .fetch()
                .rowsUpdated()
                .block();
        return user.id();
    }

    private void insertAudit(
            String id,
            String actorId,
            String action,
            String entityType,
            String entityId,
            String beforeJson,
            String afterJson,
            String result,
            String reason,
            long createdAt) {
        DatabaseClient.GenericExecuteSpec spec =
                db.sql(
                                "INSERT INTO access_audit"
                                        + " (id, actor_id, action, entity_type, entity_id, before_json,"
                                        + " after_json, result, reason, created_at)"
                                        + " VALUES (:id, :actorId, :action, :entityType, :entityId,"
                                        + " :beforeJson, :afterJson, :result, :reason, :createdAt)")
                        .bind("id", id)
                        .bind("actorId", actorId)
                        .bind("action", action)
                        .bind("entityType", entityType)
                        .bind("result", result)
                        .bind("createdAt", createdAt);
        spec = bindNullable(spec, "entityId", entityId);
        spec = bindNullable(spec, "beforeJson", beforeJson);
        spec = bindNullable(spec, "afterJson", afterJson);
        spec = bindNullable(spec, "reason", reason);
        spec.fetch().rowsUpdated().block();
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> auditPage(Map<String, Object> params) {
        Map<String, Object> body =
                (Map) client.get()
                        .uri(
                                uriBuilder -> {
                                    uriBuilder.path(AUDIT);
                                    params.forEach(uriBuilder::queryParam);
                                    return uriBuilder.build();
                                })
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    private List<Map<String, Object>> auditItems(Map<String, Object> params) {
        return itemList(auditPage(params));
    }

    private String auditBody(Map<String, Object> params) {
        return client.get()
                .uri(
                        uriBuilder -> {
                            uriBuilder.path(AUDIT);
                            params.forEach(uriBuilder::queryParam);
                            return uriBuilder.build();
                        })
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemList(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static List<String> itemIds(Map<String, Object> page) {
        return itemList(page).stream().map(row -> String.valueOf(row.get("id"))).toList();
    }

    private void expectBadRequest(Map<String, Object> params, String message) {
        client.get()
                .uri(
                        uriBuilder -> {
                            uriBuilder.path(AUDIT);
                            params.forEach(uriBuilder::queryParam);
                            return uriBuilder.build();
                        })
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo(message);
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private record Account(String id, String username) {}
}
