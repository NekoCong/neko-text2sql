package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.access.AccessContextResolver;
import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.access.EffectiveAccess;
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
 * The permission catalog and the role-permission matrix: read the catalog, read and replace one
 * role's set, with audit and next-request effect.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessControlPermissionTest {

    private static final String PASSWORD = "secret123";

    private static final String BASE = "/api/admin/access-control";

    private static final String ROLES = BASE + "/roles";

    private static final String PERMISSIONS = BASE + "/permissions";

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
    void listsTheNineSeededPermissionsWithModuleActionAndBuiltIn() {
        List<Map<String, Object>> catalog =
                (List) client.get()
                        .uri(PERMISSIONS)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBodyList(Map.class)
                        .returnResult()
                        .getResponseBody();

        assertThat(catalog).isNotEmpty();
        assertThat(catalog)
                .filteredOn(row -> Boolean.TRUE.equals(row.get("builtIn")))
                .extracting(row -> String.valueOf(row.get("key")))
                .containsExactlyInAnyOrder(
                        Permissions.CHAT_USE,
                        Permissions.DATASOURCE_READ,
                        Permissions.DATASOURCE_WRITE,
                        Permissions.MODEL_MANAGE,
                        Permissions.ACCESS_MANAGE,
                        Permissions.USER_READ,
                        Permissions.USER_MANAGE,
                        Permissions.AUDIT_READ,
                        Permissions.BYPASS_DATA_SCOPE);

        // Ordered by module then key.
        List<String> ordering =
                catalog.stream().map(row -> row.get("module") + "." + row.get("key")).toList();
        assertThat(ordering).isSorted();

        // Every entry carries its module/action split and an id.
        assertThat(catalog).allSatisfy(row -> {
            assertThat(row.get("id")).isNotNull();
            assertThat(row.get("module")).isNotNull();
            assertThat(row.get("action")).isNotNull();
            assertThat(row.get("builtIn")).isNotNull();
        });

        Map<String, Object> chatUse =
                catalog.stream()
                        .filter(row -> Permissions.CHAT_USE.equals(row.get("key")))
                        .findFirst()
                        .orElseThrow();
        assertThat(chatUse.get("module")).isEqualTo("chat");
        assertThat(chatUse.get("action")).isEqualTo("use");
        assertThat(chatUse.get("builtIn")).isEqualTo(true);
        assertThat(chatUse.get("id")).isNotNull();
    }

    @Test
    void replacesASubsetAndTheNextReadReflectsIt() {
        putPermissions(List.of(Permissions.CHAT_USE, Permissions.DATASOURCE_WRITE))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roleId")
                .isEqualTo(customRoleId)
                .jsonPath("$.permissions.length()")
                .isEqualTo(2)
                .jsonPath("$.permissions[0]")
                .isEqualTo(Permissions.CHAT_USE);

        // Replace (not merge): only the requested keys remain, and the result is ordered by key.
        putPermissions(List.of(Permissions.DATASOURCE_WRITE))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.permissions.length()")
                .isEqualTo(1)
                .jsonPath("$.permissions[0]")
                .isEqualTo(Permissions.DATASOURCE_WRITE);

        assertThat(getPermissions()).containsExactly(Permissions.DATASOURCE_WRITE);
    }

    @Test
    void duplicateKeysAreDeduplicated() {
        putPermissions(List.of(Permissions.CHAT_USE, Permissions.CHAT_USE))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.permissions.length()")
                .isEqualTo(1);

        assertThat(getPermissions()).containsExactly(Permissions.CHAT_USE);
    }

    @Test
    void anEmptyArrayClearsTheSet() {
        putPermissions(List.of(Permissions.CHAT_USE, Permissions.USER_READ))
                .expectStatus()
                .isOk();
        assertThat(getPermissions()).hasSize(2);

        putPermissions(List.of())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.permissions.length()")
                .isEqualTo(0);

        assertThat(getPermissions()).isEmpty();
    }

    @Test
    void anUnknownRoleIsNotFoundAndAudited() {
        String unknown = UUID.randomUUID().toString();

        client.put()
                .uri(ROLES + "/" + unknown + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("permissions", List.of(Permissions.CHAT_USE)))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("角色不存在");

        client.get()
                .uri(ROLES + "/" + unknown + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isNotFound();

        assertThat(auditRows(unknown, "DENIED")).isEqualTo(1);
    }

    @Test
    void anUnknownKeyIsRejectedAndAudited() {
        putPermissions(List.of(Permissions.CHAT_USE)).expectStatus().isOk();
        long successBefore = auditRows(customRoleId, "SUCCESS");

        putPermissions(List.of(Permissions.CHAT_USE, "chat.nope"))
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("未知权限：chat.nope");

        assertThat(getPermissions())
                .as("a rejected request does not change the set")
                .containsExactly(Permissions.CHAT_USE);
        assertThat(auditRows(customRoleId, "DENIED")).isEqualTo(1);
        assertThat(auditRows(customRoleId, "SUCCESS")).isEqualTo(successBefore);
    }

    @Test
    void aStudentCannotReadOrReplacePermissions() {
        String username = unique("student");
        AppUser student =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Student", Roles.STUDENT)
                        .block();
        access.assignRoleByCode(student.id(), Roles.STUDENT).block();
        String token = "Bearer " + TestAuth.login(client, username, PASSWORD);

        client.get()
                .uri(PERMISSIONS)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isForbidden();

        client.get()
                .uri(ROLES + "/" + customRoleId + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isForbidden();

        client.put()
                .uri(ROLES + "/" + customRoleId + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("permissions", List.of(Permissions.ACCESS_MANAGE)))
                .exchange()
                .expectStatus()
                .isForbidden();

        assertThat(getPermissions()).isEmpty();
        assertThat(auditRows(customRoleId, "SUCCESS")).isZero();
    }

    @Test
    void eachReplaceWritesOneSuccessRowWithSortedBeforeAndAfter() {
        putPermissions(List.of(Permissions.USER_READ, Permissions.CHAT_USE))
                .expectStatus()
                .isOk();
        putPermissions(List.of(Permissions.USER_READ))
                .expectStatus()
                .isOk();

        assertThat(auditRows(customRoleId, "SUCCESS")).isEqualTo(2);

        Map<String, Object> latest = latestAuditRow(customRoleId, "SUCCESS");
        assertThat(String.valueOf(latest.get("action"))).isEqualTo("role.permissions");
        assertThat(String.valueOf(latest.get("entity_type"))).isEqualTo("ROLE");
        assertThat(String.valueOf(latest.get("before_json")))
                .isEqualTo("[\"" + Permissions.CHAT_USE + "\",\"" + Permissions.USER_READ + "\"]");
        assertThat(String.valueOf(latest.get("after_json")))
                .isEqualTo("[\"" + Permissions.USER_READ + "\"]");
    }

    @Test
    void aGrantedPermissionAppliesToTheNextRequest() {
        AppUser user =
                users.insert(unique("u"), passwordEncoder.encode(PASSWORD), "Tester", Roles.STUDENT)
                        .block();
        assign(user.id(), customRoleId);
        assertThat(resolve(user.id()).hasPermission(Permissions.DATASOURCE_WRITE)).isFalse();

        putPermissions(List.of(Permissions.DATASOURCE_WRITE)).expectStatus().isOk();

        EffectiveAccess after = resolve(user.id());
        assertThat(after.hasPermission(Permissions.DATASOURCE_WRITE))
                .as("the edited set applies on the next resolution, without a new token")
                .isTrue();
        assertThat(after.roles()).extracting(role -> role.id()).contains(customRoleId);
    }

    // --- helpers ----------------------------------------------------------------------------

    private String bearer() {
        return "Bearer " + adminToken;
    }

    private WebTestClient.ResponseSpec putPermissions(List<String> keys) {
        return client.put()
                .uri(ROLES + "/" + customRoleId + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("permissions", keys))
                .exchange();
    }

    @SuppressWarnings("unchecked")
    private List<String> getPermissions() {
        Map<String, Object> body =
                client.get()
                        .uri(ROLES + "/" + customRoleId + "/permissions")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("roleId")).isEqualTo(customRoleId);
        return (List<String>) body.getOrDefault("permissions", List.of());
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
        String roleId = "test-role-perm-" + suffix;
        long now = System.currentTimeMillis();
        db.sql(
                        "INSERT INTO access_role (id, code, name, description, parent_id, built_in,"
                                + " enabled, created_at, updated_at)"
                                + " VALUES (:id, :code, :name, NULL, NULL, FALSE, TRUE, :now, :now)")
                .bind("id", roleId)
                .bind("code", "perm-" + suffix)
                .bind("name", "Perm " + suffix)
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
                        .bind("action", "role.permissions")
                        .bind("entityId", entityId)
                        .bind("result", result)
                        .map((row, meta) -> row.get("n", Long.class))
                        .one()
                        .block();
        return count == null ? 0L : count;
    }

    private Map<String, Object> latestAuditRow(String entityId, String result) {
        return db.sql(
                        "SELECT action, entity_type, before_json, after_json, result FROM access_audit"
                                + " WHERE actor_id = :actorId AND action = :action"
                                + " AND entity_id = :entityId AND result = :result"
                                + " ORDER BY created_at DESC, id DESC LIMIT 1")
                .bind("actorId", adminId)
                .bind("action", "role.permissions")
                .bind("entityId", entityId)
                .bind("result", result)
                .map((row, meta) -> Map.<String, Object>of(
                        "action", String.valueOf(row.get("action", String.class)),
                        "entity_type", String.valueOf(row.get("entity_type", String.class)),
                        "before_json", String.valueOf(row.get("before_json", String.class)),
                        "after_json", String.valueOf(row.get("after_json", String.class)),
                        "result", String.valueOf(row.get("result", String.class))))
                .one()
                .block();
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
