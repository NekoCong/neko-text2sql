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
 * The user lifecycle admin API: list, create, enable/disable, reset password and assign roles, with
 * token-version bumps, the last-administrator lockout and one audit row per mutation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessControlUserTest {

    private static final String PASSWORD = "secret123";

    private static final String USERS = "/api/admin/access-control/users";

    private static final String REASON_LAST_ADMIN = "不能移除最后一个拥有 access.manage 的管理员";

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

    @Test
    void createsAUserThatAppearsInTheListWithItsRoleAndAHashOnlyInTheDatabase() {
        String username = unique("s");
        String studentRoleId = roleIdByCode(Roles.STUDENT);

        String id =
                createUser(
                        username,
                        "张三",
                        List.of(studentRoleId));

        assertThat(id).isNotBlank();
        assertThat(userIdByUsername(username)).isEqualTo(id);

        // The created user appears in the list with its role.
        List<Map<String, Object>> rows = listUsers(username);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("username")).isEqualTo(username);
        assertThat(row.get("displayName")).isEqualTo("张三");
        assertThat(row.get("enabled")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roles = (List<Map<String, Object>>) row.get("roles");
        assertThat(roles).extracting(r -> r.get("code")).containsExactly(Roles.STUDENT);

        // The password exists only as a BCrypt hash, never as the raw value.
        String hash = passwordHash(username);
        assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    void rejectsADuplicateUsernameAndAnUnknownRole() {
        String username = unique("s");
        String studentRoleId = roleIdByCode(Roles.STUDENT);
        createUser(username, "重复", List.of(studentRoleId));

        client.post()
                .uri(USERS)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", PASSWORD, "roleIds", List.of(studentRoleId)))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("用户名已存在");

        client.post()
                .uri(USERS)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "username",
                                unique("s"),
                                "password",
                                PASSWORD,
                                "roleIds",
                                List.of(UUID.randomUUID().toString())))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .value(value -> assertThat(String.valueOf(value)).startsWith("角色不存在："));
    }

    @Test
    void filtersByQueryEnabledAndRoleId() {
        String prefix = unique("flt");
        String studentRoleId = roleIdByCode(Roles.STUDENT);
        String adminRoleId = roleIdByCode(Roles.ADMIN);

        String enabledStudent = createUser(prefix + "a", "苹果", List.of(studentRoleId));
        String disabledStudent = createUser(prefix + "b", "香蕉", List.of(studentRoleId));
        createUser(prefix + "c", "管理员", List.of(adminRoleId));

        setStatus(disabledStudent, false).expectStatus().isOk();

        // query matches username/display name (contains, case-insensitive).
        assertThat(usernames(prefix.toUpperCase(java.util.Locale.ROOT), null, null))
                .containsExactlyInAnyOrder(prefix + "a", prefix + "b", prefix + "c");
        assertThat(usernames("苹果", null, null)).containsExactly(prefix + "a");

        // enabled filter.
        assertThat(usernames(prefix, true, null))
                .containsExactlyInAnyOrder(prefix + "a", prefix + "c");
        assertThat(usernames(prefix, false, null)).containsExactly(prefix + "b");

        // roleId filter keeps only users directly assigned that role.
        assertThat(usernames(prefix, null, studentRoleId))
                .containsExactlyInAnyOrder(prefix + "a", prefix + "b");
        assertThat(usernames(prefix, null, adminRoleId)).containsExactly(prefix + "c");

        assertThat(enabledStudent).isNotEqualTo(disabledStudent);
    }

    @Test
    void disableAndEnableBumpTokenVersionAndRejectTheOldToken() {
        String username = unique("dis");
        String id = createUser(username, "禁用", List.of(roleIdByCode(Roles.STUDENT)));
        String token = TestAuth.login(client, username, PASSWORD);
        int before = tokenVersion(id);

        setStatus(id, false)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(false);
        assertThat(tokenVersion(id)).isEqualTo(before + 1);

        // The token issued before the disable is rejected on the next request.
        client.get()
                .uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isUnauthorized();

        setStatus(id, true)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(true);
        assertThat(tokenVersion(id)).isEqualTo(before + 2);

        // A fresh login works again.
        assertThat(TestAuth.login(client, username, PASSWORD)).isNotBlank();
    }

    @Test
    void resetPasswordInvalidatesTheOldPasswordAndBumpsTokenVersion() {
        String username = unique("pw");
        String id = createUser(username, "改密", List.of(roleIdByCode(Roles.STUDENT)));
        String oldPassword = "oldsecret1";
        String newPassword = "newsecret2";
        // The user was created with the shared PASSWORD; reset twice to control both secrets.
        resetPassword(id, oldPassword).expectStatus().isNoContent();
        assertThat(passwordEncoder.matches(oldPassword, passwordHash(username))).isTrue();
        int before = tokenVersion(id);

        resetPassword(id, newPassword).expectStatus().isNoContent();
        assertThat(tokenVersion(id)).isEqualTo(before + 1);

        // The old password no longer works, the new one logs in.
        loginExpects(username, oldPassword, false);
        assertThat(TestAuth.login(client, username, newPassword)).isNotBlank();
        assertThat(passwordEncoder.matches(newPassword, passwordHash(username))).isTrue();
    }

    @Test
    void rolesReplaceReflectEmptyClearsAndUnknownRoleIsNotFound() {
        String username = unique("rl");
        String id = createUser(username, "角色", List.of(roleIdByCode(Roles.STUDENT)));
        String adminRoleId = roleIdByCode(Roles.ADMIN);

        setRoles(id, List.of(adminRoleId))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roles[0].code")
                .isEqualTo(Roles.ADMIN);

        setRoles(id, List.of())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roles.length()")
                .isEqualTo(0);

        setRoles(id, List.of(UUID.randomUUID().toString()))
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .value(value -> assertThat(String.valueOf(value)).startsWith("角色不存在："));
    }

    @Test
    void theLastEnabledAccessManageUserCannotBeDisabledOrStripped() {
        disableEveryOtherAdmin(adminId);

        setStatus(adminId, false)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo(REASON_LAST_ADMIN);

        setRoles(adminId, List.of(roleIdByCode(Roles.STUDENT)))
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo(REASON_LAST_ADMIN);

        // Nothing changed: still enabled, still holding the admin role.
        assertThat(enabled(adminId)).isTrue();
        assertThat(directRoleCodes(adminId)).containsExactly(Roles.ADMIN);
    }

    @Test
    void withASecondAdminTheSameCallsSucceed() {
        disableEveryOtherAdmin(adminId);

        String secondUsername = unique("admin2");
        String secondId = createUser(secondUsername, "第二管理员", List.of(roleIdByCode(Roles.ADMIN)));
        String secondToken = TestAuth.login(client, secondUsername, PASSWORD);
        assertThat(secondId).isNotBlank();

        setStatusAs(secondToken, adminId, false).expectStatus().isOk().expectBody().jsonPath("$.enabled").isEqualTo(false);
        setStatusAs(secondToken, adminId, true).expectStatus().isOk().expectBody().jsonPath("$.enabled").isEqualTo(true);
        setRolesAs(secondToken, adminId, List.of(roleIdByCode(Roles.STUDENT)))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roles[0].code")
                .isEqualTo(Roles.STUDENT);
    }

    @Test
    void aStudentCannotUseAnyUserRoute() {
        String username = unique("student");
        AppUser student =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Student", Roles.STUDENT)
                        .block();
        access.assignRoleByCode(student.id(), Roles.STUDENT).block();
        String token = "Bearer " + TestAuth.login(client, username, PASSWORD);
        String studentRoleId = roleIdByCode(Roles.STUDENT);

        client.get().uri(USERS).header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isForbidden();
        client.post()
                .uri(USERS)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", unique("nope"), "password", PASSWORD, "roleIds", List.of(studentRoleId)))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.put()
                .uri(USERS + "/" + student.id() + "/status")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri(USERS + "/" + student.id() + "/reset-password")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", "newsecret2"))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.put()
                .uri(USERS + "/" + student.id() + "/roles")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("roleIds", List.of(studentRoleId)))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void everyMutationWritesOneSecretFreeSuccessRowAndARejectedRoleChangeOneDeniedRow() {
        String username = unique("aud");
        String id = createUser(username, "审计", List.of(roleIdByCode(Roles.STUDENT)));

        setStatus(id, false).expectStatus().isOk();
        setStatus(id, true).expectStatus().isOk();
        resetPassword(id, "newsecret9").expectStatus().isNoContent();
        setRoles(id, List.of(roleIdByCode(Roles.ADMIN))).expectStatus().isOk();

        assertThat(auditRows("user.create", id, "SUCCESS")).isEqualTo(1);
        assertThat(auditRows("user.disable", id, "SUCCESS")).isEqualTo(1);
        assertThat(auditRows("user.enable", id, "SUCCESS")).isEqualTo(1);
        assertThat(auditRows("user.password_reset", id, "SUCCESS")).isEqualTo(1);
        assertThat(auditRows("user.roles", id, "SUCCESS")).isEqualTo(1);

        // The rejected role change writes exactly one DENIED row.
        setRoles(id, List.of(UUID.randomUUID().toString())).expectStatus().isNotFound();
        assertThat(auditRows("user.roles", id, "DENIED")).isEqualTo(1);

        // No audit payload ever carries the raw password or the hash column.
        assertAuditSecretFree(id, PASSWORD, "newsecret9", "password_hash");
    }

    @Test
    void roleChangesTakeEffectOnTheNextRequestWithoutBumpingTokenVersion() {
        String freshRoleId = createRoleGranting(Permissions.ACCESS_MANAGE);
        String studentRoleId = roleIdByCode(Roles.STUDENT);
        String username = unique("perm");
        String id = createUser(username, "权限", List.of(studentRoleId));
        String token = TestAuth.login(client, username, PASSWORD);
        int versionBefore = tokenVersion(id);

        // Without access.manage, the admin route is forbidden.
        client.get()
                .uri("/api/admin/access-control/roles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isForbidden();

        // Grant access.manage via a role change.
        setRoles(id, List.of(freshRoleId)).expectStatus().isOk();
        assertThat(tokenVersion(id))
                .as("role changes must not bump token_version")
                .isEqualTo(versionBefore);

        // The same token now passes the permission gate — no re-login.
        client.get()
                .uri("/api/admin/access-control/roles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk();

        // Removing the role again takes effect on the very next request with the same token.
        setRoles(id, List.of(studentRoleId)).expectStatus().isOk();
        assertThat(tokenVersion(id)).isEqualTo(versionBefore);
        client.get()
                .uri("/api/admin/access-control/roles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void anUnknownUserIsNotFound() {
        String unknown = UUID.randomUUID().toString();

        setStatus(unknown, false)
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("用户不存在");

        setRoles(unknown, List.of(roleIdByCode(Roles.STUDENT)))
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("用户不存在");

        resetPassword(unknown, "newsecret9")
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("用户不存在");
    }

    @Test
    void aNoOpStatusChangeReturns200AndWritesNoAuditRow() {
        String id = createUser(unique("noop"), "不变", List.of(roleIdByCode(Roles.STUDENT)));

        // Setting enabled=true on an already-enabled user is a no-op: 200, no audit row.
        setStatus(id, true).expectStatus().isOk().expectBody().jsonPath("$.enabled").isEqualTo(true);
        assertThat(auditRows("user.enable", id, "SUCCESS")).isZero();

        setStatus(id, false).expectStatus().isOk();
        assertThat(auditRows("user.disable", id, "SUCCESS")).isEqualTo(1);

        // Repeating the disable is a no-op: still 200, and no additional audit row.
        setStatus(id, false).expectStatus().isOk().expectBody().jsonPath("$.enabled").isEqualTo(false);
        assertThat(auditRows("user.disable", id, "SUCCESS")).isEqualTo(1);
        assertThat(auditRows("user.enable", id, "SUCCESS")).isZero();
    }

    // --- helpers ----------------------------------------------------------------------------

    private String bearer() {
        return "Bearer " + adminToken;
    }

    private String createUser(String username, String displayName, List<String> roleIds) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        if (displayName != null) {
            body.put("displayName", displayName);
        }
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

    private WebTestClient.ResponseSpec setStatus(String id, boolean enabled) {
        return setStatusAs(adminToken, id, enabled);
    }

    private WebTestClient.ResponseSpec setStatusAs(String token, String id, boolean enabled) {
        return client.put()
                .uri(USERS + "/" + id + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", enabled))
                .exchange();
    }

    private WebTestClient.ResponseSpec setRoles(String id, List<String> roleIds) {
        return setRolesAs(adminToken, id, roleIds);
    }

    private WebTestClient.ResponseSpec setRolesAs(String token, String id, List<String> roleIds) {
        return client.put()
                .uri(USERS + "/" + id + "/roles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("roleIds", roleIds))
                .exchange();
    }

    private WebTestClient.ResponseSpec resetPassword(String id, String password) {
        return client.post()
                .uri(USERS + "/" + id + "/reset-password")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", password))
                .exchange();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, Object>> listUsers(String query) {
        return (List) client.get()
                .uri(uriBuilder -> uriBuilder.path(USERS).queryParam("query", query).build())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(Map.class)
                .returnResult()
                .getResponseBody();
    }

    private List<String> usernames(String query, Boolean enabled, String roleId) {
        return client.get()
                .uri(
                        uriBuilder -> {
                            uriBuilder.path(USERS);
                            if (query != null) {
                                uriBuilder.queryParam("query", query);
                            }
                            if (enabled != null) {
                                uriBuilder.queryParam("enabled", enabled);
                            }
                            if (roleId != null) {
                                uriBuilder.queryParam("roleId", roleId);
                            }
                            return uriBuilder.build();
                        })
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(Map.class)
                .returnResult()
                .getResponseBody()
                .stream()
                .map(row -> String.valueOf(row.get("username")))
                .toList();
    }

    private void loginExpects(String username, String password, boolean success) {
        WebTestClient.ResponseSpec spec =
                client.post()
                        .uri("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("username", username, "password", password))
                        .exchange();
        if (success) {
            spec.expectStatus().isOk();
        } else {
            spec.expectStatus().isUnauthorized();
        }
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

    /** Creates a fresh, non built-in role that grants the given permission, and returns its id. */
    private String createRoleGranting(String permissionKey) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String roleId = "test-role-user-" + suffix;
        long now = System.currentTimeMillis();
        db.sql(
                        "INSERT INTO access_role (id, code, name, description, parent_id, built_in,"
                                + " enabled, created_at, updated_at)"
                                + " VALUES (:id, :code, :name, NULL, NULL, FALSE, TRUE, :now, :now)")
                .bind("id", roleId)
                .bind("code", "userperm" + suffix)
                .bind("name", "Perm " + suffix)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .block();
        String permissionId =
                db.sql("SELECT id FROM access_permission WHERE permission_key = :key")
                        .bind("key", permissionKey)
                        .map((row, meta) -> row.get("id", String.class))
                        .one()
                        .block();
        assertThat(permissionId).as("permission " + permissionKey).isNotNull();
        db.sql("INSERT INTO access_role_permission (role_id, permission_id) VALUES (:roleId, :permissionId)")
                .bind("roleId", roleId)
                .bind("permissionId", permissionId)
                .fetch()
                .rowsUpdated()
                .block();
        return roleId;
    }

    private String userIdByUsername(String username) {
        return db.sql("SELECT id FROM app_user WHERE username = :username")
                .bind("username", username)
                .map((row, meta) -> row.get("id", String.class))
                .one()
                .block();
    }

    private String passwordHash(String username) {
        return db.sql("SELECT password_hash FROM app_user WHERE username = :username")
                .bind("username", username)
                .map((row, meta) -> row.get("password_hash", String.class))
                .one()
                .block();
    }

    private int tokenVersion(String id) {
        Integer version =
                db.sql("SELECT token_version FROM app_user WHERE id = :id")
                        .bind("id", id)
                        .map((row, meta) -> row.get("token_version", Integer.class))
                        .one()
                        .block();
        return version == null ? 0 : version;
    }

    private boolean enabled(String id) {
        Boolean value =
                db.sql("SELECT enabled FROM app_user WHERE id = :id")
                        .bind("id", id)
                        .map((row, meta) -> Boolean.TRUE.equals(row.get("enabled", Boolean.class)))
                        .one()
                        .block();
        return Boolean.TRUE.equals(value);
    }

    private List<String> directRoleCodes(String userId) {
        return db.sql(
                        "SELECT r.code FROM access_role r"
                                + " JOIN access_user_role ur ON ur.role_id = r.id"
                                + " WHERE ur.user_id = :userId ORDER BY r.code")
                .bind("userId", userId)
                .map((row, meta) -> row.get("code", String.class))
                .all()
                .collectList()
                .block();
    }

    private long auditRows(String action, String entityId, String result) {
        Long count =
                db.sql(
                                "SELECT COUNT(*) AS n FROM access_audit"
                                        + " WHERE actor_id = :actorId AND action = :action"
                                        + " AND entity_id = :entityId AND result = :result")
                        .bind("actorId", adminId)
                        .bind("action", action)
                        .bind("entityId", entityId)
                        .bind("result", result)
                        .map((row, meta) -> row.get("n", Long.class))
                        .one()
                        .block();
        return count == null ? 0L : count;
    }

    private void assertAuditSecretFree(String entityId, String... secrets) {
        List<String[]> payloads =
                db.sql(
                                "SELECT before_json, after_json FROM access_audit WHERE entity_id = :entityId")
                        .bind("entityId", entityId)
                        .map(
                                (row, meta) ->
                                        new String[] {
                                            row.get("before_json", String.class),
                                            row.get("after_json", String.class)
                                        })
                        .all()
                        .collectList()
                        .block();
        for (String[] row : payloads) {
            for (String payload : row) {
                if (payload == null) {
                    continue;
                }
                for (String secret : secrets) {
                    assertThat(payload)
                            .as("audit payload must not leak secret %s", secret)
                            .doesNotContain(secret);
                }
            }
        }
    }

    /** Disables every enabled account that holds {@code access.manage} except the given subject. */
    private void disableEveryOtherAdmin(String subjectId) {
        List<String> ids =
                db.sql("SELECT id FROM app_user WHERE enabled = TRUE")
                        .map((row, meta) -> row.get("id", String.class))
                        .all()
                        .collectList()
                        .block();
        for (String id : ids) {
            if (id.equals(subjectId)) {
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

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
