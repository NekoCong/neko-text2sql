package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifies that the account status and session version are re-checked on every request.
 *
 * <p>Registration assigns the built-in {@code student} role; disabling the account or bumping
 * {@code token_version} invalidates every token issued before the change, while a fresh login keeps
 * working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessStatusIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "secret123";

    @LocalServerPort
    private int port;

    @Autowired
    private DatabaseClient client;

    private WebTestClient web;

    @BeforeEach
    void setUp() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void disabledAccountAndStaleTokenVersionAreRejected() {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body =
                web.post()
                        .uri("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("username", username, "password", PASSWORD))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(String.class)
                        .returnResult()
                        .getResponseBody();
        String token = jsonField(body, "token");
        String userId = jsonField(body, "user", "id");

        me(token).expectStatus().isOk();

        Long roles =
                client.sql("SELECT COUNT(*) AS n FROM access_user_role WHERE user_id = :id"
                                + " AND role_id = 'test-role-student'")
                        .bind("id", userId)
                        .map((row, meta) -> ((Number) row.get("n")).longValue())
                        .one()
                        .block();
        assertThat(roles).isEqualTo(1L);

        exec("UPDATE app_user SET enabled = FALSE WHERE id = :id", userId);
        me(token).expectStatus().isUnauthorized();

        exec("UPDATE app_user SET enabled = TRUE, token_version = token_version + 1 WHERE id = :id", userId);
        me(token).expectStatus().isUnauthorized();

        String fresh = TestAuth.login(web, username, PASSWORD);
        me(fresh).expectStatus().isOk().expectBody().jsonPath("$.username").isEqualTo(username);
    }

    private WebTestClient.ResponseSpec me(String token) {
        return web.get()
                .uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private void exec(String sql, String userId) {
        client.sql(sql).bind("id", userId).fetch().rowsUpdated().block();
    }

    private static String jsonField(String body, String... path) {
        try {
            var node = MAPPER.readTree(body);
            for (String part : path) {
                node = node.get(part);
            }
            return node.asText();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse JSON field", ex);
        }
    }
}
