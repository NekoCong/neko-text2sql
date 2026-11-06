package com.nekocong.text2sql.knowledge.store;

import com.nekocong.text2sql.knowledge.KnowledgeType;

/**
 * One similarity (or lexical) hit, carrying everything needed to build a citation without a second
 * query.
 *
 * @param chunkId the chunk id
 * @param itemId the owning item
 * @param knowledgeType the collection it came from
 * @param title the item title
 * @param tableName the table a metadata item describes, or {@code null}
 * @param content the chunk text
 * @param score the cosine similarity or lexical score
 * @param documentId the owning document, or {@code null}
 * @param pageNo the 1-based page number, or {@code null}
 * @param bbox the normalized box, or {@code null}
 * @param charStart the start offset in the text snapshot, or {@code null}
 * @param charEnd the end offset in the text snapshot, or {@code null}
 * @param quote the original quote
 */
public record VectorHit(
        String chunkId,
        String itemId,
        KnowledgeType knowledgeType,
        String title,
        String tableName,
        String content,
        double score,
        String documentId,
        Integer pageNo,
        int[] bbox,
        Integer charStart,
        Integer charEnd,
        String quote) {
}
