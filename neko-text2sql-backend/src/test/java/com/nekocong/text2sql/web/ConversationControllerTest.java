package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.service.ConversationService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Web-layer tests for conversation management (the history tree).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private ConversationService conversations;

    private WebTestClient client;
    private String token;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        token = TestAuth.registerAndLogin(client);
    }

    private String createConversation() {
        String body =
                client.post()
                        .uri("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("title", "Orders analysis", "datasourceId", "campus"))
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

    @Test
    void managesConversationLifecycle() {
        String id = createConversation();

        client.get()
                .uri("/api/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
                .isEqualTo(id);

        client.get()
                .uri("/api/conversations/{id}/messages", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(0);

        client.put()
                .uri("/api/conversations/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("title", "Renamed"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Renamed");

        client.delete()
                .uri("/api/conversations/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isNoContent();

        client.get()
                .uri("/api/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(0);
    }

    @Test
    void conversationIsIsolatedPerUser() {
        String id = createConversation();
        String otherToken = TestAuth.registerAndLogin(client);

        client.get()
                .uri("/api/conversations/{id}/messages", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    /**
     * Guards the shared error contract: a {@link ResponseStatusException} from an unrelated endpoint
     * keeps Spring's default body ({@code timestamp/status/error/message/path}), not the
     * {@code {status, message}} shape the role admin API uses. If a global
     * {@code @ExceptionHandler(ResponseStatusException.class)} is ever added to the application advice
     * again, this fails.
     */
    @Test
    void unknownConversationKeepsTheDefaultErrorBodyShape() {
        String id = createConversation();
        String otherToken = TestAuth.registerAndLogin(client);

        client.get()
                .uri("/api/conversations/{id}/messages", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                // The default Spring error body carries these; the role API's shape does not.
                .jsonPath("$.timestamp")
                .exists()
                .jsonPath("$.status")
                .isEqualTo(404)
                .jsonPath("$.error")
                .isEqualTo("Not Found")
                .jsonPath("$.path")
                .exists()
                .jsonPath("$.message")
                .doesNotExist();
    }

    @Test
    void historyEmbedsRowsAndChartAsRealJson() {
        String id = createConversation();
        String userId = TestAuth.currentUserId(client, token);
        String chart = "{\"type\":\"TABLE\",\"xField\":null,\"yField\":null,\"title\":\"成绩\"}";
        String rows =
                "{\"columns\":[{\"name\":\"score\",\"typeName\":\"DOUBLE\"}],"
                        + "\"rows\":[{\"score\":92.0}],\"rowCount\":1,\"truncated\":false,\"elapsedMs\":3}";
        conversations.appendAssistantMessage(id, "答案", "SELECT 1", chart, rows).block();

        client.get()
                .uri("/api/conversations/{id}/messages", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].rows.rowCount")
                .isEqualTo(1)
                .jsonPath("$[0].rows.rows[0].score")
                .isEqualTo(92.0)
                .jsonPath("$[0].chart.type")
                .isEqualTo("TABLE");
    }
}
