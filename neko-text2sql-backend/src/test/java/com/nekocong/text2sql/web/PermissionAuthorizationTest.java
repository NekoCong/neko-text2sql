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

/**
 * Route authorization follows the permissions resolved from the access-control tables, not the JWT
 * {@code role} claim.
 *
 * <p>A custom role can hold {@code access.manage} / {@code datasource.write} without being named
 * {@code admin}, and an account whose token claims {@code admin} but whose configured role lacks the
 * permission is rejected: the claim never grants a route.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PermissionAuthorizationTest {

    private static final String PASSWORD = "secret123";

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
    void customRoleWithAccessManageMayReadTheAdminApiWithoutBeingAdmin() {
        String roleId = createRole(Permissions.ACCESS_MANAGE);
        AppUser user = createUser(Roles.STUDENT);
        assign(user.id(), roleId);
        String token = TestAuth.login(client, user.username(), PASSWORD);

        client.get()
                .uri("/api/admin/datasource-access")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roles")
                .exists();
    }

    @Test
    void adminRoleClaimWithoutTheConfiguredPermissionIsRejected() {
        AppUser user = createUser(Roles.ADMIN);
        // The seeded student role holds chat.use + datasource.read only.
        access.assignRoleByCode(user.id(), Roles.STUDENT).block();
        String token = TestAuth.login(client, user.username(), PASSWORD);

        // Sanity check: the token really does claim admin.
        assertThat(TestAuth.currentUserId(client, token)).isEqualTo(user.id());

        client.get()
                .uri("/api/admin/datasource-access")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void customRoleWithDatasourceWriteMayMutateDatasourcesWhileChatOnlyIsRejected() {
        String writerRole = createRole(Permissions.DATASOURCE_WRITE);
        AppUser writer = createUser(Roles.STUDENT);
        assign(writer.id(), writerRole);
        String writerToken = TestAuth.login(client, writer.username(), PASSWORD);

        String id = "ds" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        client.put()
                .uri("/api/text2sql/datasources/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(definition(id, "chat-only-writer"))
                .exchange()
                // Reaches the controller: unknown id is the controller's 404, not a 403.
                .expectStatus()
                .isNotFound();

        client.delete()
                .uri("/api/text2sql/datasources/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writerToken)
                .exchange()
                .expectStatus()
                .isNotFound();

        String chatOnlyRole = createRole(Permissions.CHAT_USE);
        AppUser chatOnly = createUser(Roles.STUDENT);
        assign(chatOnly.id(), chatOnlyRole);
        String chatOnlyToken = TestAuth.login(client, chatOnly.username(), PASSWORD);

        client.put()
                .uri("/api/text2sql/datasources/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + chatOnlyToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(definition(id, "chat-only-writer"))
                .exchange()
                .expectStatus()
                .isForbidden();

        client.delete()
                .uri("/api/text2sql/datasources/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + chatOnlyToken)
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    private static String definition(String id, String name) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"driver\":\"org.h2.Driver\","
                + "\"url\":\"jdbc:h2:mem:" + id + "\",\"username\":\"sa\",\"password\":\"\","
                + "\"dialect\":\"H2\"}";
    }

    /** Creates a custom (non built-in) role holding exactly the given permission keys. */
    private String createRole(String... permissionKeys) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String roleId = "test-role-custom-" + suffix;
        long now = System.currentTimeMillis();
        db.sql(
                        "INSERT INTO access_role (id, code, name, description, parent_id, built_in,"
                                + " enabled, created_at, updated_at)"
                                + " VALUES (:id, :code, :name, NULL, NULL, FALSE, TRUE, :now, :now)")
                .bind("id", roleId)
                .bind("code", "custom-" + suffix)
                .bind("name", "Custom " + suffix)
                .bind("now", now)
                .fetch()
                .rowsUpdated()
                .block();

        for (String key : permissionKeys) {
            String permissionId = db.sql(
                            "SELECT id FROM access_permission WHERE permission_key = :key")
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

    /** Creates a user with a unique username and the given legacy {@code app_user.role}. */
    private AppUser createUser(String legacyRole) {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return users.insert(username, passwordEncoder.encode(PASSWORD), "Tester", legacyRole).block();
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
}
