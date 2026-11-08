package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.user.AppUser;
import java.util.Map;
import java.util.UUID;
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
 * Web-layer tests for the role-based data scope on conversations.
 *
 * <p>An admin may read and modify another user's conversation; a regular user may not, and gets a
 * {@code 404} (not a {@code 403}) so the conversation's existence is not leaked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationRoleAccessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "secret123";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccessControlRepository access;

    private WebTestClient client;
    private String ownerToken;
    private String otherToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        ownerToken = TestAuth.registerAndLogin(client);
        otherToken = TestAuth.registerAndLogin(client);
        String adminName = "admin" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AppUser admin = users.insert(adminName, passwordEncoder.encode(PASSWORD), "Admin", Roles.ADMIN).block();
        access.assignRoleByCode(admin.id(), Roles.ADMIN).block();
        adminToken = TestAuth.login(client, adminName, PASSWORD);
    }

    private String createConversation(String token) {
        String body =
                client.post()
                        .uri("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("title", "Scoped", "datasourceId", "campus"))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(String.class)
                        .returnResult()
                        .getResponseBody();
        try {
            return MAPPER.readTree(body).get("id").asText();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String listConversations(String token) {
        return client.get()
                .uri("/api/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    @Test
    void nonAdminCannotReachAnotherUsersConversation() {
        String id = createConversation(ownerToken);

        client.get()
                .uri("/api/conversations/{id}/messages", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange()
                .expectStatus()
                .isNotFound();

        client.put()
                .uri("/api/conversations/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("title", "Hijacked"))
                .exchange()
                .expectStatus()
                .isNotFound();

        client.delete()
                .uri("/api/conversations/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void nonAdminListExcludesAnotherUsersConversation() {
        String id = createConversation(ownerToken);

        assertThat(listConversations(otherToken)).doesNotContain(id);
    }

    @Test
    void adminCanReachAndRenameAnotherUsersConversation() {
        String id = createConversation(ownerToken);

        client.get()
                .uri("/api/conversations/{id}/messages", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus()
                .isOk();

        client.put()
                .uri("/api/conversations/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("title", "Renamed by admin"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Renamed by admin");
    }

    @Test
    void adminListIncludesAnotherUsersConversation() {
        String id = createConversation(ownerToken);

        assertThat(listConversations(adminToken)).contains(id);
    }

    @Test
    void registrationNeverGrantsAdmin() {
        // TestAuth registers through the public endpoint, which must always yield a plain student.
        client.get()
                .uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.role")
                .isEqualTo(Roles.STUDENT);
    }
}
