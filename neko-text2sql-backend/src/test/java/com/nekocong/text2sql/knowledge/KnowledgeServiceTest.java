package com.nekocong.text2sql.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nekocong.text2sql.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Exercises the knowledge base in its default (lexical) mode — no embedding key required.
 */
@SpringBootTest
class KnowledgeServiceTest {

    @Autowired
    private KnowledgeService knowledge;

    @Test
    void ingestsSearchesAndDeletes() {
        DocumentInfo info =
                knowledge.ingest(
                        "it-kb-en",
                        "orders-guide",
                        "How to count orders per city. Use GROUP BY city. Only include paid orders.");
        assertThat(info.chunks()).isPositive();
        assertThat(knowledge.list("it-kb-en")).anyMatch(doc -> doc.name().equals("orders-guide"));

        var hits = knowledge.search("it-kb-en", "count orders", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).text()).containsIgnoringCase("orders");
        assertThat(hits.get(0).score()).isGreaterThan(0.0);

        assertThat(knowledge.remove("it-kb-en", "orders-guide")).isPositive();
        assertThat(knowledge.list("it-kb-en")).isEmpty();
    }

    @Test
    void matchesChineseSubstrings() {
        knowledge.ingest("it-kb-zh", "手册", "统计每个城市的订单总金额，只统计已支付订单。");
        var hits = knowledge.search("it-kb-zh", "订单总金额", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).score()).isGreaterThan(0.0);
        knowledge.remove("it-kb-zh", "手册");
    }

    @Test
    void rejectsInvalidIngest() {
        assertThatThrownBy(() -> knowledge.ingest("it-kb-bad", " ", "content"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> knowledge.ingest("it-kb-bad", "name", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
