package com.nekocong.text2sql.knowledge.parse;

import java.util.List;
import java.util.Map;

/**
 * The normalized result of parsing one document.
 *
 * @param plainText the concatenated text, used for character-range citations
 * @param blocks the ordered blocks
 * @param pageCount the page count (1 for text-only sources)
 * @param engine the engine that produced this result
 * @param engineVersion the engine version
 * @param derivedFiles derived artifacts keyed by relative path ({@code full.md},
 *     {@code content.json}, {@code pages/1.png}); the ingestion service stores them as-is
 */
public record ParsedDocument(
        String plainText,
        List<ParsedBlock> blocks,
        int pageCount,
        String engine,
        String engineVersion,
        Map<String, byte[]> derivedFiles) {
}
