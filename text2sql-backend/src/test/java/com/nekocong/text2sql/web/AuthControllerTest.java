package com.nekocong.text2sql.web;

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
}
