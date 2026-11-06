package com.nekocong.text2sql.knowledge.parse;

/**
 * A pluggable document parser.
 *
 * <p>Text-oriented engines produce character ranges; layout engines additionally produce page
 * numbers and normalized bounding boxes, which is what makes original-PDF highlighting possible.
 */
public interface DocumentParser {

    /** @return the engine id recorded on the document */
    String engine();

    /** @return the engine version recorded on the document */
    String engineVersion();

    /** @return {@code true} when this engine is configured and reachable */
    boolean available();

    /**
     * Reports whether this engine can handle the input.
     *
     * @param context the input
     * @return {@code true} when the engine supports the format
     */
    boolean supports(ParseContext context);

    /**
     * Parses the document.
     *
     * @param context the input
     * @return the normalized result
     * @throws ParseException when parsing fails or produces no usable text
     */
    ParsedDocument parse(ParseContext context);
}
