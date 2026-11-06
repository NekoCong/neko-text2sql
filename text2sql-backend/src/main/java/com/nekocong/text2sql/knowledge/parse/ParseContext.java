package com.nekocong.text2sql.knowledge.parse;

/**
 * The input to a parser.
 *
 * @param filename the original filename
 * @param mimeType the detected media type
 * @param bytes the original bytes
 * @param sourceUrl the source URL for URL imports, otherwise {@code null}
 */
public record ParseContext(String filename, String mimeType, byte[] bytes, String sourceUrl) {
}
