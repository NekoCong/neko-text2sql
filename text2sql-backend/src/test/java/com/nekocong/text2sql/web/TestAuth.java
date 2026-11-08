package com.nekocong.text2sql.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Test helper: registers a fresh user and returns a JWT, so protected endpoints can be exercised
 * end-to-end.
 */
final class TestAuth {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestAuth() {}

    /** Registers a random user and returns its access token. */
    static String registerAndLogin(WebTestClient client) {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        client.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of("username", username, "password", "secret123", "displayName", "Tester"))
                .exchange()
                .expectStatus()
                .isCreated();
        return login(client, username, "secret123");
    }

    /** Logs in and returns the access token. */
    static String login(WebTestClient client, String username, String password) {
        String body =
                client.post()
                        .uri("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("username", username, "password", password))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(String.class)
                        .returnResult()
                        .getResponseBody();
        try {
            return MAPPER.readTree(body).get("token").asText();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse login token", ex);
        }
    }

    /** Reads the current user's id from {@code /api/auth/me}. */
    static String currentUserId(WebTestClient client, String token) {
        String body =
                client.get()
                        .uri("/api/auth/me")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(String.class)
                        .returnResult()
                        .getResponseBody();
        try {
            return MAPPER.readTree(body).get("id").asText();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse current user", ex);
        }
    }
}
