package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.user.AppUser;
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
 * End-to-end authentication tests: registration, login, current-user, and access control on a
 * protected endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerTest {

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
    void registerLoginAndMe() {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        client.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", "secret123", "displayName", "Alice"))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.token")
                .isNotEmpty()
                .jsonPath("$.user.username")
                .isEqualTo(username);

        String token = TestAuth.login(client, username, "secret123");

        client.get()
                .uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.username")
                .isEqualTo(username);
    }

    @Test
    void legacyRoleAccountCanStillLogInAndKeepsItsRole() {
        // Accounts created before the campus dataset carry the legacy "user" role. Signing in must
        // not blow up on an unrecognized role claim; the permission layer rejects the scope later.
        String username = "legacy" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        AppUser user = users.insert(username, passwordEncoder.encode("secret123"), "Legacy", Roles.USER).block();
        // The account-status filter requires at least one enabled role, so assign one here.
        access.assignRoleByCode(user.id(), Roles.STUDENT).block();

        String token = TestAuth.login(client, username, "secret123");

        client.get()
                .uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.role")
                .isEqualTo(Roles.USER);
    }

    @Test
    void duplicateUsernameIsRejected() {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, String> body =
                Map.of("username", username, "password", "secret123", "displayName", "Bob");
        client.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isCreated();
        client.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isEqualTo(409);
    }

    @Test
    void wrongPasswordIsRejected() {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        client.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", "secret123"))
                .exchange()
                .expectStatus()
                .isCreated();
        client.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", "wrong-password"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void protectedEndpointRequiresToken() {
        client.get().uri("/api/conversations").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void meReturnsEffectiveRolesAndPermissionsForAdmin() {
        String username = unique("adm");
        AppUser admin = users.insert(username, passwordEncoder.encode("secret123"), "Admin", Roles.ADMIN).block();
        access.assignRoleByCode(admin.id(), Roles.ADMIN).block();

        String token = TestAuth.login(client, username, "secret123");

        client.get()
                .uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roles[0]")
                .isEqualTo("admin")
                .jsonPath("$.permissions")
                .value(value -> assertThat((List<Object>) value)
                        .contains("access.manage", "user.manage", "audit.read"))
                .jsonPath("$.password")
                .doesNotExist()
                .jsonPath("$.passwordHash")
                .doesNotExist();
    }

    @Test
    void registrationReturnsStudentRoleAndPermissions() {
        String username = unique("u");
        client.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", "secret123", "displayName", "Alice"))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.token")
                .isNotEmpty()
                .jsonPath("$.user.username")
                .isEqualTo(username)
                .jsonPath("$.user.roles")
                .value(value -> assertThat((List<Object>) value).containsExactly("student"))
                .jsonPath("$.user.permissions")
                .value(value -> assertThat((List<Object>) value).containsExactly("chat.use", "datasource.read"))
                .jsonPath("$.user.password")
                .doesNotExist()
                .jsonPath("$.user.passwordHash")
                .doesNotExist();
    }

    @Test
    void meReflectsARoleChangeOnTheNextCall() {
        String username = unique("u");
        client.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", "secret123"))
                .exchange()
                .expectStatus()
                .isCreated();

        String token = TestAuth.login(client, username, "secret123");
        String userId = TestAuth.currentUserId(client, token);

        // A freshly registered account starts as a student.
        client.get()
                .uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roles[0]")
                .isEqualTo("student")
                .jsonPath("$.permissions")
                .value(value -> assertThat((List<Object>) value).doesNotContain("access.manage"));

        // The administrator reassigns the account to the admin role.
        replaceRole(userId, Roles.ADMIN);

        // The very next /me reflects the new role and its permissions.
        client.get()
                .uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roles[0]")
                .isEqualTo("admin")
                .jsonPath("$.permissions")
                .value(value -> assertThat((List<Object>) value)
                        .contains("access.manage", "user.manage", "audit.read"));
    }

    /** Replaces every role assignment of a user with the single role carrying the given code. */
    private void replaceRole(String userId, String roleCode) {
        db.sql("DELETE FROM access_user_role WHERE user_id = :userId")
                .bind("userId", userId)
                .fetch()
                .rowsUpdated()
                .block();
        access.assignRoleByCode(userId, roleCode).block();
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
