package com.nekocong.text2sql.web;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web-layer tests for per-user model configuration (CRUD; the API key is never returned).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelConfigControllerTest {

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
    private String token;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        AppUser admin =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Admin", Roles.ADMIN).block();
        access.assignRoleByCode(admin.id(), Roles.ADMIN).block();
        token = TestAuth.login(client, username, PASSWORD);
    }

    @Test
    void managesModelConfigs() {
        String body =
                client.post()
                        .uri("/api/model-configs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(
                                Map.of(
                                        "name", "My Qwen",
                                        "provider", "dashscope",
                                        "model", "qwen-plus",
                                        "apiKey", "sk-secret"))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(String.class)
                        .returnResult()
                        .getResponseBody();

        JsonNode created;
        String id;
        try {
            created = MAPPER.readTree(body);
            id = created.get("id").asText();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        // The API key must never be returned.
        assertThat(created.path("hasApiKey").asBoolean()).isTrue();
        assertThat(created.path("isDefault").asBoolean()).isTrue();
        assertThat(created.has("apiKey")).isFalse();

        client.get()
                .uri("/api/model-configs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
                .isEqualTo(id);

        client.put()
                .uri("/api/model-configs/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Renamed", "provider", "dashscope", "model", "qwen-max"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("Renamed")
                .jsonPath("$.model")
                .isEqualTo("qwen-max")
                // Blank key on update keeps the stored one.
                .jsonPath("$.hasApiKey")
                .isEqualTo(true);

        client.delete()
                .uri("/api/model-configs/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    void rejectsUnknownProvider() {
        client.post()
                .uri("/api/model-configs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "gemini", "model", "x"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
}
