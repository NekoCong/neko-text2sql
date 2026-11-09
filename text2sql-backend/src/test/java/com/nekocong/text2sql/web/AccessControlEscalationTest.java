package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.access.AccessContextResolver;
import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.access.Permissions;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.user.AppUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * The anti-escalation and lockout invariants: an administrator cannot grant permissions (directly or
 * via role inheritance/assignment) they do not hold, the built-in admin role is protected, and the
 * system always keeps at least one usable {@code access.manage} holder. Route gating is verified but
 * never re-routed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessControlEscalationTest {

    private static final String PASSWORD = "secret123";

    private static final String BASE = "/api/admin/access-control";

    private static final String ROLES = BASE + "/roles";

    private static final String PERMISSIONS = BASE + "/permissions";

    private static final String USERS = BASE + "/users";

    private static final String DATA_SCOPES = BASE + "/data-scopes";

    private static final String DATASOURCE_ACCESS = BASE + "/datasource-access";

    private static final String REASON_GRANT = "不能授予自己未持有的权限：";

    private static final String REASON_PARENT = "不能继承超出自身权限的角色：";

    private static final String REASON_ASSIGN = "不能分配超出自身权限的角色：";

    private static final String REASON_LAST_ADMIN = "不能移除最后一个拥有 access.manage 的管理员";

    private static final String REASON_BUILT_IN_ADMIN_PERMISSIONS =
            "内置管理员角色必须保留 access.manage 和 access.bypass_data_scope";

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

    // --- anti-escalation --------------------------------------------------------------------

    @Test
    void nonAdminCannotGrantAPermissionTheyLackButTheBuiltInAdminCan() {
        Operator operator = newOperator(Permissions.ACCESS_MANAGE);
        String operatorToken = "Bearer " + login(operator.username());

        String targetRoleId = createRoleGranting();

        putPermissionsAs(operatorToken, targetRoleId, List.of(Permissions.BYPASS_DATA_SCOPE))
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo(REASON_GRANT + Permissions.BYPASS_DATA_SCOPE);

        assertThat(auditRows(operator.id(), "role.permissions", targetRoleId, "DENIED")).isEqualTo(1);
        assertThat(auditRows(operator.id(), "role.permissions", targetRoleId, "SUCCESS")).isZero();

        // The built-in admin (the bootstrap exception) may grant the same key.
        putPermissions(targetRoleId, List.of(Permissions.BYPASS_DATA_SCOPE))
                .expectStatus()
                .isOk();
        assertThat(auditRows(adminId, "role.permissions", targetRoleId, "SUCCESS")).isEqualTo(1);
    }

    @Test
    void nonAdminCannotCreateOrUpdateARoleUnderARoleThatGrantsWhatTheyLack() {
        Operator operator = newOperator(Permissions.ACCESS_MANAGE);
        String operatorToken = "Bearer " + login(operator.username());

        String parentRoleId = createRoleGranting(Permissions.BYPASS_DATA_SCOPE);
        String parentCode = roleCode(parentRoleId);

        client.post()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, operatorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", unique("esc"), "name", "越权子", "parentId", parentRoleId))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo(REASON_PARENT + parentCode);

        String childRoleId = createRoleGranting();
        putAs(operatorToken, childRoleId, Map.of("parentId", parentRoleId))
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo(REASON_PARENT + parentCode);

        assertThat(auditRows(operator.id(), "role.create", parentRoleId, "DENIED")).isEqualTo(1);
        assertThat(auditRows(operator.id(), "role.update", childRoleId, "DENIED")).isEqualTo(1);
    }

    @Test
    void nonAdminCannotAssignARoleThatGrantsWhatTheyLack() {
        Operator operator = newOperator(Permissions.ACCESS_MANAGE, Permissions.USER_MANAGE);
        String operatorToken = "Bearer " + login(operator.username());

        String targetRoleId = createRoleGranting(Permissions.BYPASS_DATA_SCOPE);
        String targetCode = roleCode(targetRoleId);

        String targetUsername = unique("tgt");
        String targetId = createUser(targetUsername, createRoleGranting());

        putRolesAs(operatorToken, targetId, List.of(targetRoleId))
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo(REASON_ASSIGN + targetCode);

        assertThat(auditRows(operator.id(), "user.roles", targetId, "DENIED")).isEqualTo(1);
        // The DENIED row reports the requested role codes, not raw ids.
        assertThat(auditColumn(operator.id(), "user.roles", targetId, "DENIED", "after_json"))
                .anySatisfy(json -> assertThat(json).contains(targetCode));
    }

    @Test
    void builtInAdminRoleIsProtectedFromReparentDisableAndPermissionRemoval() {
        String adminRoleId = roleIdByCode(Roles.ADMIN);
        String otherRoleId = createRoleGranting();

        put(adminRoleId, Map.of("parentId", otherRoleId))
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("内置管理员角色不能调整继承关系");

        put(adminRoleId, Map.of("enabled", false))
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("内置管理员角色不能停用");

        putPermissions(adminRoleId, List.of(Permissions.BYPASS_DATA_SCOPE))
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo(REASON_BUILT_IN_ADMIN_PERMISSIONS);

        assertThat(auditRows(adminId, "role.update", adminRoleId, "DENIED")).isEqualTo(1);
        assertThat(auditRows(adminId, "role.disable", adminRoleId, "DENIED")).isEqualTo(1);
        assertThat(auditRows(adminId, "role.permissions", adminRoleId, "DENIED")).isEqualTo(1);
    }

    // --- last-administrator lockout ---------------------------------------------------------

    @Test
    void removingTheLastAdminsAccessManageViaPermissionReplaceIsRejected() {
        Holder holder = singleAdminHolder();

        putPermissionsAs("Bearer " + holder.token(), holder.roleId(), List.of())
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo(REASON_LAST_ADMIN);

        assertThat(auditRows(holder.userId(), "role.permissions", holder.roleId(), "DENIED")).isEqualTo(1);
    }

    @Test
    void disablingTheLastAdminsRoleIsRejected() {
        Holder holder = singleAdminHolder();

        putAs("Bearer " + holder.token(), holder.roleId(), Map.of("enabled", false))
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo(REASON_LAST_ADMIN);

        assertThat(auditRows(holder.userId(), "role.disable", holder.roleId(), "DENIED")).isEqualTo(1);
    }

    @Test
    void reparentingAwayTheLastAdminsAccessManageIsRejected() {
        String parentId = createRoleGranting(Permissions.ACCESS_MANAGE);
        String childId = createRoleWithParent(parentId);
        String username = unique("holder");
        String userId = createUser(username, childId);
        String token = login(username);
        disableEveryAdminExcept(Set.of(userId));

        putAs("Bearer " + token, childId, Map.of("parentId", ""))
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo(REASON_LAST_ADMIN);

        assertThat(auditRows(userId, "role.update", childId, "DENIED")).isEqualTo(1);
    }

    @Test
    void withASecondAdminTheSameChangesSucceed() {
        String roleId = createRoleGranting(Permissions.ACCESS_MANAGE);
        String username = unique("holder");
        String userId = createUser(username, roleId);
        String token = login(username);

        String secondUsername = unique("admin2");
        AppUser second = users.insert(secondUsername, passwordEncoder.encode(PASSWORD), "Second", Roles.ADMIN).block();
        access.assignRoleByCode(second.id(), Roles.ADMIN).block();

        disableEveryAdminExcept(Set.of(userId, second.id()));

        putPermissionsAs("Bearer " + token, roleId, List.of())
                .expectStatus()
                .isOk();
        assertThat(auditRows(userId, "role.permissions", roleId, "SUCCESS")).isEqualTo(1);
    }

    // --- route gating (verify, do not re-route) --------------------------------------------

    @Test
    void rolePermissionDataScopeAndDatasourceRoutesRequireAccessManageNotUserManage() {
        String manageRoleId = createRoleGranting(Permissions.USER_MANAGE);
        String username = unique("um");
        createUser(username, manageRoleId);
        String token = "Bearer " + login(username);

        client.get().uri(ROLES).header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isForbidden();
        client.get().uri(PERMISSIONS).header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isForbidden();
        client.get().uri(DATASOURCE_ACCESS).header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isForbidden();
        client.get()
                .uri(uriBuilder -> uriBuilder.path(DATA_SCOPES).queryParam("roleId", roleIdByCode(Roles.STUDENT)).build())
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void userReadGatesReadsAndUserManageGatesWrites() {
        String readRoleId = createRoleGranting(Permissions.USER_READ);
        String readUsername = unique("rd");
        String readUserId = createUser(readUsername, readRoleId);
        String readToken = "Bearer " + login(readUsername);

        client.get().uri(USERS).header(HttpHeaders.AUTHORIZATION, readToken).exchange().expectStatus().isOk();
        client.get().uri(USERS + "/" + readUserId).header(HttpHeaders.AUTHORIZATION, readToken).exchange().expectStatus().isOk();
        client.post()
                .uri(USERS)
                .header(HttpHeaders.AUTHORIZATION, readToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", unique("nope"), "password", PASSWORD, "roleIds", List.of()))
                .exchange()
                .expectStatus()
                .isForbidden();

        String manageRoleId = createRoleGranting(Permissions.USER_MANAGE);
        String manageUsername = unique("wm");
        createUser(manageUsername, manageRoleId);
        String manageToken = "Bearer " + login(manageUsername);

        client.get().uri(USERS).header(HttpHeaders.AUTHORIZATION, manageToken).exchange().expectStatus().isForbidden();
        client.post()
                .uri(USERS)
                .header(HttpHeaders.AUTHORIZATION, manageToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", unique("ok"), "password", PASSWORD, "roleIds", List.of(manageRoleId)))
                .exchange()
                .expectStatus()
                .isCreated();
    }

    // --- helpers ----------------------------------------------------------------------------

    private String bearer() {
        return "Bearer " + adminToken;
    }

    private WebTestClient.ResponseSpec put(String roleId, Map<String, Object> body) {
        return putAs(bearer(), roleId, body);
    }

    private WebTestClient.ResponseSpec putAs(String bearer, String roleId, Map<String, Object> body) {
        return client.put()
                .uri(ROLES + "/" + roleId)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private WebTestClient.ResponseSpec putPermissions(String roleId, List<String> keys) {
        return putPermissionsAs(bearer(), roleId, keys);
    }

    private WebTestClient.ResponseSpec putPermissionsAs(String bearer, String roleId, List<String> keys) {
        return client.put()
                .uri(ROLES + "/" + roleId + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("permissions", keys))
                .exchange();
    }

    private WebTestClient.ResponseSpec putRolesAs(String bearer, String userId, List<String> roleIds) {
        return client.put()
                .uri(USERS + "/" + userId + "/roles")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("roleIds", roleIds))
                .exchange();
    }

    /** Creates a fresh, non built-in role granting the given permission keys, and returns its id. */
    private String createRoleGranting(String... keys) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String roleId = "test-role-esc-" + suffix;
        long now = System.currentTimeMillis();
        db.sql(
                        "INSERT INTO access_role (id, code, name, description, parent_id, built_in,"
                                + " enabled, created_at, updated_at)"
                                + " VALUES (:id, :code, :name, NULL, NULL, FALSE, TRUE, :now, :now)")
                .bind("id", roleId)
                .bind("code", "esc" + suffix)
                .bind("name", "Esc " + suffix)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .block();
        for (String key : keys) {
            grantPermission(roleId, key);
        }
        return roleId;
    }

    /** Creates a fresh role with the given parent (no direct permissions), and returns its id. */
    private String createRoleWithParent(String parentId) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String roleId = "test-role-child-" + suffix;
        long now = System.currentTimeMillis();
        db.sql(
                        "INSERT INTO access_role (id, code, name, description, parent_id, built_in,"
                                + " enabled, created_at, updated_at)"
                                + " VALUES (:id, :code, :name, NULL, :parentId, FALSE, TRUE, :now, :now)")
                .bind("id", roleId)
                .bind("code", "child" + suffix)
                .bind("name", "Child " + suffix)
                .bind("parentId", parentId)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .block();
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

    /** Creates a user with the given role (both under the access-control tables) and returns its id. */
    private String createUser(String username, String roleId) {
        AppUser user = users.insert(username, passwordEncoder.encode(PASSWORD), "Tester", Roles.STUDENT).block();
        assignRoleDirectly(user.id(), roleId);
        return user.id();
    }

    private void assignRoleDirectly(String userId, String roleId) {
        db.sql("INSERT INTO access_user_role (user_id, role_id, created_at) VALUES (:u, :r, :now)")
                .bind("u", userId)
                .bind("r", roleId)
                .bind("now", System.currentTimeMillis())
                .fetch()
                .rowsUpdated()
                .block();
    }

    private String login(String username) {
        return TestAuth.login(client, username, PASSWORD);
    }

    /** Creates a non-admin operator holding the given keys. */
    private Operator newOperator(String... keys) {
        String username = unique("op");
        String roleId = createRoleGranting(keys);
        String id = createUser(username, roleId);
        return new Operator(id, username);
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

    private String roleCode(String roleId) {
        return db.sql("SELECT code FROM access_role WHERE id = :id")
                .bind("id", roleId)
                .map((row, meta) -> row.get("code", String.class))
                .one()
                .block();
    }

    private Holder singleAdminHolder() {
        String roleId = createRoleGranting(Permissions.ACCESS_MANAGE);
        String username = unique("holder");
        String userId = createUser(username, roleId);
        String token = login(username);
        disableEveryAdminExcept(Set.of(userId));
        return new Holder(roleId, userId, token);
    }

    /** Disables every enabled account that holds {@code access.manage} except the given ids. */
    private void disableEveryAdminExcept(Set<String> keep) {
        List<String> ids =
                db.sql("SELECT id FROM app_user WHERE enabled = TRUE")
                        .map((row, meta) -> row.get("id", String.class))
                        .all()
                        .collectList()
                        .block();
        for (String id : ids) {
            if (keep.contains(id)) {
                continue;
            }
            Boolean holds =
                    resolver
                            .effectivePermissions(id)
                            .map(permissions -> permissions.contains(Permissions.ACCESS_MANAGE))
                            .block();
            if (Boolean.TRUE.equals(holds)) {
                db.sql("UPDATE app_user SET enabled = FALSE, token_version = token_version + 1 WHERE id = :id")
                        .bind("id", id)
                        .fetch()
                        .rowsUpdated()
                        .block();
            }
        }
    }

    private long auditRows(String actorId, String action, String entityId, String result) {
        Long count =
                db.sql(
                                "SELECT COUNT(*) AS n FROM access_audit"
                                        + " WHERE actor_id = :actorId AND action = :action"
                                        + " AND entity_id = :entityId AND result = :result")
                        .bind("actorId", actorId)
                        .bind("action", action)
                        .bind("entityId", entityId)
                        .bind("result", result)
                        .map((row, meta) -> row.get("n", Long.class))
                        .one()
                        .block();
        return count == null ? 0L : count;
    }

    private List<String> auditColumn(String actorId, String action, String entityId, String result, String column) {
        return db.sql(
                        "SELECT DISTINCT " + column + " AS v FROM access_audit"
                                + " WHERE actor_id = :actorId AND action = :action"
                                + " AND entity_id = :entityId AND result = :result")
                .bind("actorId", actorId)
                .bind("action", action)
                .bind("entityId", entityId)
                .bind("result", result)
                .map((row, meta) -> row.get("v", String.class))
                .all()
                .collectList()
                .block();
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private record Operator(String id, String username) {}

    private record Holder(String roleId, String userId, String token) {}
}
