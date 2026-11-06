package com.nekocong.text2sql.web;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Web-layer tests for the knowledge-base endpoints (authenticated, JSON round-trip incl. Chinese).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KnowledgeControllerTest {

    @LocalServerPort
    private int port;

    private WebTestClient client;
    private String token;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        token = TestAuth.registerAndLogin(client);
    }

    @Test
    void ingestsSearchesAndDeletes() {
        client.post()
                .uri("/api/text2sql/knowledge/legacy/documents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "namespace", "it-kb-ctrl",
                                "name", "guide",
                                "content", "统计每个城市的订单总金额：使用 GROUP BY city 聚合 orders.total_amount。"))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.chunks")
                .isNumber();

        client.post()
                .uri("/api/text2sql/knowledge/legacy/search")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("namespace", "it-kb-ctrl", "query", "订单总金额", "topK", 3))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].score")
                .isNumber();

        client.delete()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/text2sql/knowledge/legacy/documents")
                                        .queryParam("namespace", "it-kb-ctrl")
                                        .queryParam("name", "guide")
                                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.removed")
                .isNumber();
    }
}
