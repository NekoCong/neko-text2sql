package com.nekocong.text2sql.knowledge.api.dto;

import java.util.List;

/**
 * The normalized text snapshot of a document, for the Office/URL/text preview mode.
 *
 * @param documentId the document id
 * @param plainText the full text the block offsets refer to
 * @param pageCount the page count
 * @param blocks the parsed blocks
 */
public record DocumentContentView(
        String documentId, String plainText, int pageCount, List<ContentBlockView> blocks) {
}
