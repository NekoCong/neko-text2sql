package com.nekocong.text2sql.knowledge;

/**
 * A retrievable fragment of a knowledge item.
 *
 * <p>Chunks never span pages: {@code pageNo}, {@code bbox} and the character range therefore
 * describe exactly one highlightable region of the original.
 *
 * @param id the chunk id
 * @param itemId the owning item
 * @param documentId the owning document, or {@code null} for text-only items
 * @param chunkIndex the ordinal within the item
 * @param content the chunk text
 * @param pageNo the 1-based page number, or {@code null} for text-only sources
 * @param bbox the normalized {@code [x0,y0,x1,y1]} box (0–1000), or {@code null}
 * @param charStart the start offset in the plain text, or {@code null}
 * @param charEnd the end offset in the plain text, or {@code null}
 * @param quote the original quote shown to the user
 * @param embedding the vector, or {@code null} when embeddings are unavailable
 */
public record KnowledgeChunk(
        String id,
        String itemId,
        String documentId,
        int chunkIndex,
        String content,
        Integer pageNo,
        int[] bbox,
        Integer charStart,
        Integer charEnd,
        String quote,
        double[] embedding) {
}
