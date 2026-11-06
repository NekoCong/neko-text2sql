package com.nekocong.text2sql.knowledge.parse;

/**
 * One parsed content block.
 *
 * @param blockId a stable id within the document
 * @param type the block type (text, title, table, image, ...)
 * @param text the block text (may be empty for images)
 * @param pageNo the 1-based page number, or {@code 0} when the source has no pages
 * @param bbox the normalized {@code [x0,y0,x1,y1]} box (0–1000), or {@code null}
 * @param charStart the start offset in {@link ParsedDocument#plainText()}
 * @param charEnd the end offset in {@link ParsedDocument#plainText()}
 * @param imagePath the derived image path, or {@code null}
 */
public record ParsedBlock(
        String blockId,
        String type,
        String text,
        int pageNo,
        int[] bbox,
        int charStart,
        int charEnd,
        String imagePath) {
}
