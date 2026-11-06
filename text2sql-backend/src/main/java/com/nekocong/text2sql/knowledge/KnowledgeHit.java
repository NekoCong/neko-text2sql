package com.nekocong.text2sql.knowledge;

/**
 * One retrieval hit.
 *
 * @param itemId the knowledge item
 * @param knowledgeType the collection it came from
 * @param title the display title
 * @param content the matched text
 * @param score the similarity / lexical score
 * @param citation the original-source evidence, or {@code null} for metadata items
 */
public record KnowledgeHit(
        String itemId,
        KnowledgeType knowledgeType,
        String title,
        String content,
        double score,
        Citation citation) {
}
