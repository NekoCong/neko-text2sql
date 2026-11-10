package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.access.Permissions;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.user.AppUser;
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

/** Verifies that module permissions are enforced by the server routes, independently of the UI. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PermissionRouteGatingTest {

    private static final String PASSWORD = "secret123";

    private static final String CONVERSATIONS = "/api/conversations";

    private static final String DATASOURCES = "/api/text2sql/datasources";

    private static final String MODEL_CONFIGS = "/api/model-configs";

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

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void chatUseOnlyAllowsConversations() {
        String token = tokenWithPermissions(Permissions.CHAT_USE);

        get(token, CONVERSATIONS).expectStatus().isOk();
        get(token, DATASOURCES).expectStatus().isForbidden();
        get(token, MODEL_CONFIGS).expectStatus().isForbidden();
    }

    @Test
    void datasourceReadOnlyAllowsDatasourceReads() {
        String token = datasourceReadToken();

        get(token, DATASOURCES).expectStatus().isOk();
        get(token, CONVERSATIONS).expectStatus().isForbidden();
        get(token, MODEL_CONFIGS).expectStatus().isForbidden();
    }

    @Test
    void modelManageOnlyAllowsModelConfigs() {
        String token = tokenWithPermissions(Permissions.MODEL_MANAGE);

        get(token, MODEL_CONFIGS).expectStatus().isOk();
        get(token, CONVERSATIONS).expectStatus().isForbidden();
        get(token, DATASOURCES).expectStatus().isForbidden();
    }

    @Test
    void datasourceReadDoesNotAllowDatasourceWrites() {
        String token = datasourceReadToken();
        String id = "route-ds-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        client.post()
                .uri(DATASOURCES)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(definition(id))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.put()
                .uri(DATASOURCES + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(definition(id))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.delete()
                .uri(DATASOURCES + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void adminCanUseEveryModule() {
        String username = unique("route-admin");
        AppUser admin =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Admin", Roles.ADMIN).block();
        access.assignRoleByCode(admin.id(), Roles.ADMIN).block();
        String token = TestAuth.login(client, username, PASSWORD);

        get(token, CONVERSATIONS).expectStatus().isOk();
        get(token, DATASOURCES).expectStatus().isOk();
        get(token, MODEL_CONFIGS).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec get(String token, String uri) {
        return client.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .exchange();
    }

    private String datasourceReadToken() {
        String username = unique("route-user");
        AppUser user =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Tester", Roles.STUDENT)
                        .block();
        String roleId = createRole(Permissions.DATASOURCE_READ);
        // The list controller resolves a data scope after the route check. An explicit DENY rule
        // supplies a valid empty scope without adding a permission or a datasource grant.
        long now = System.currentTimeMillis();
        db.sql(
                        "INSERT INTO access_data_scope (id, role_id, datasource_id, table_name, policy,"
                                + " column_name, principal_attribute, created_at, updated_at)"
                                + " VALUES (:id, :role, 'campus', 'course', 'DENY', NULL, NULL, :now, :now)")
                .bind("id", roleId.replace("test-role-route", "test-scope-route"))
                .bind("role", roleId)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .block();
        assign(user.id(), roleId);
        return TestAuth.login(client, username, PASSWORD);
    }

    private String tokenWithPermissions(String... permissionKeys) {
        String username = unique("route-user");
        AppUser user =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Tester", Roles.STUDENT)
                        .block();
        assign(user.id(), createRole(permissionKeys));
        return TestAuth.login(client, username, PASSWORD);
    }

    /** Creates a fresh role holding exactly the supplied permissions. */
    private String createRole(String... permissionKeys) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String roleId = "test-role-route-" + suffix;
        long now = System.currentTimeMillis();
        db.sql(
                        "INSERT INTO access_role (id, code, name, description, parent_id, built_in,"
                                + " enabled, created_at, updated_at)"
                                + " VALUES (:id, :code, :name, NULL, NULL, FALSE, TRUE, :now, :now)")
                .bind("id", roleId)
                .bind("code", "route-" + suffix)
                .bind("name", "Route " + suffix)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .block();

        for (String key : permissionKeys) {
            String permissionId =
                    db.sql("SELECT id FROM access_permission WHERE permission_key = :key")
                            .bind("key", key)
                            .map((row, meta) -> row.get("id", String.class))
                            .one()
                            .block();
            assertThat(permissionId).as("seeded permission " + key).isNotNull();
            db.sql("INSERT INTO access_role_permission (role_id, permission_id) VALUES (:role, :perm)")
                    .bind("role", roleId)
                    .bind("perm", permissionId)
                    .fetch()
                    .rowsUpdated()
                    .block();
        }
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

    private static String definition(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"Route gate\",\"driver\":\"org.h2.Driver\","
                + "\"url\":\"jdbc:h2:mem:" + id + "\",\"username\":\"sa\",\"password\":\"\","
                + "\"dialect\":\"H2\"}";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
