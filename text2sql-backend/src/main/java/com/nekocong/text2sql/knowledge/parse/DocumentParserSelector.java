package com.nekocong.text2sql.knowledge.parse;

import com.nekocong.text2sql.config.KnowledgeProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Chooses the parser implementation for a document based on its format and the configured engines.
 *
 * <p>This class has no Spring annotation on purpose: the application wiring creates it as a bean.
 * PDFs and images use {@code parser.pdf-engine} / {@code parser.image-engine}; every other format
 * uses Tika. An explicitly selected MinerU engine that is not configured fails instead of silently
 * falling back to Tika, so callers never get a text-only result that cannot be highlighted.
 */
public class DocumentParserSelector {

    private static final String MINERU = "mineru";
    private static final String TIKA = "tika";

    private final KnowledgeProperties properties;
    private final Map<String, DocumentParser> parsers;

    /**
     * Creates the selector.
     *
     * @param properties the knowledge configuration holding the engine choices
     * @param parsers the available parsers, keyed by {@link DocumentParser#engine()}
     */
    public DocumentParserSelector(KnowledgeProperties properties, List<DocumentParser> parsers) {
        this.properties = Objects.requireNonNull(properties, "properties");
        Map<String, DocumentParser> byEngine = new LinkedHashMap<>();
        if (parsers != null) {
            for (DocumentParser parser : parsers) {
                if (parser == null || parser.engine() == null) {
                    continue;
                }
                byEngine.put(parser.engine().trim().toLowerCase(Locale.ROOT), parser);
            }
        }
        this.parsers = Collections.unmodifiableMap(byEngine);
    }

    /**
     * Selects the configured engine for the input format.
     *
     * @param context the input
     * @return the parser to use
     * @throws ParseException when no configured engine can handle the input or the chosen engine is
     *     unavailable
     */
    public DocumentParser select(ParseContext context) {
        Objects.requireNonNull(context, "context");
        String engine;
        if (DocumentFormats.isPdf(context)) {
            engine = properties.getParser().getPdfEngine();
        } else if (DocumentFormats.isImage(context)) {
            engine = properties.getParser().getImageEngine();
        } else {
            engine = TIKA;
        }
        return select(engine, context);
    }

    /**
     * Selects a named engine, used when re-parsing a document with an explicitly chosen engine.
     *
     * @param engineName the engine id ({@code tika}, {@code mineru})
     * @param context the input
     * @return the parser to use
     * @throws ParseException when the engine is unknown, unavailable or does not support the input
     */
    public DocumentParser select(String engineName, ParseContext context) {
        Objects.requireNonNull(context, "context");
        String key = engineName == null ? "" : engineName.trim().toLowerCase(Locale.ROOT);
        DocumentParser parser = parsers.get(key);
        if (parser == null) {
            throw new ParseException("未知的解析引擎: " + (key.isEmpty() ? "(空)" : engineName));
        }
        if (MINERU.equals(key) && !parser.available()) {
            throw new ParseException(
                    "MinerU 未配置 Token，无法解析 PDF/图片；请配置 MINERU_TOKEN 或切换解析引擎");
        }
        if (!parser.available()) {
            throw new ParseException("解析引擎 " + key + " 当前不可用");
        }
        if (!parser.supports(context)) {
            throw new ParseException("解析引擎 " + key + " 不支持该文档格式: " + describe(context));
        }
        return parser;
    }

    /**
     * Returns the configured engine ids.
     *
     * @return the engine ids in registration order
     */
    public List<String> engines() {
        return List.copyOf(parsers.keySet());
    }

    /**
     * Reports whether any registered engine supports the input format.
     *
     * @param context the input
     * @return {@code true} when a parser reports support for the format
     */
    public boolean supports(ParseContext context) {
        if (context == null) {
            return false;
        }
        for (DocumentParser parser : parsers.values()) {
            if (parser.supports(context)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(ParseContext context) {
        String filename = context.filename() == null || context.filename().isBlank()
                ? "(无文件名)"
                : context.filename();
        String mime = context.mimeType() == null || context.mimeType().isBlank()
                ? "(未知类型)"
                : context.mimeType();
        return filename + " [" + mime + "]";
    }
}
