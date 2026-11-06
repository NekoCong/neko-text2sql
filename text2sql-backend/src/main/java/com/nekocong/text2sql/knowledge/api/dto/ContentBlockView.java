package com.nekocong.text2sql.knowledge.api.dto;

import java.util.List;

/**
 * One parsed block of a document, used by the text preview to highlight a character range.
 *
 * @param blockId the stable block id
 * @param type the block type (text, title, table, image, ...)
 * @param text the block text
 * @param pageNo the 1-based page number
 * @param bbox the normalized box, or {@code null}
 * @param charStart the start offset in the plain text
 * @param charEnd the end offset in the plain text
 * @param imagePath the extracted image path, or {@code null}
 */
public record ContentBlockView(
        String blockId,
        String type,
        String text,
        Integer pageNo,
        List<Integer> bbox,
        Integer charStart,
        Integer charEnd,
        String imagePath) {
}
