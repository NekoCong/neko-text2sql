package com.nekocong.text2sql.knowledge.parse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.config.KnowledgeProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

/**
 * Local Tika-backed parser for Office, text, Markdown, CSV, HTML and (when selected) PDF documents.
 *
 * <p>Text is split into one block per non-empty line, which keeps every {@code charStart}/{@code
 * charEnd} range exact and makes character citations possible without layout metadata. The parser
 * is stateless apart from a thread-safe {@link ObjectMapper}, so it can be called concurrently.
 */
public class TikaDocumentParser implements DocumentParser {

    private static final String ENGINE = "tika";
    private static final String FALLBACK_VERSION = "3.2.3";
    private static final int WRITE_LIMIT = 100 * 1024 * 1024;
    private static final String SENTENCE_END = ".。!?！？;；,，:：";

    private final KnowledgeProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates the parser.
     *
     * @param properties the knowledge configuration (used for the upload size limit)
     */
    public TikaDocumentParser(KnowledgeProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public String engine() {
        return ENGINE;
    }

    @Override
    public String engineVersion() {
        return resolveTikaVersion();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean supports(ParseContext context) {
        return context != null && (DocumentFormats.isPdf(context) || DocumentFormats.isText(context));
    }

    @Override
    public ParsedDocument parse(ParseContext context) {
        Objects.requireNonNull(context, "context");
        byte[] bytes = context.bytes();
        if (bytes == null || bytes.length == 0) {
            throw new ParseException("未从文档中提取到可用文本");
        }
        if (bytes.length > maxBytes()) {
            throw new ParseException("文档超过大小上限 " + properties.getParser().getMaxFileSizeMb() + " MB");
        }

        Metadata metadata = new Metadata();
        if (context.filename() != null && !context.filename().isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, context.filename());
        }
        if (context.mimeType() != null && !context.mimeType().isBlank()) {
            metadata.set(TikaCoreProperties.CONTENT_TYPE_HINT, context.mimeType());
        }

        BodyContentHandler handler = new BodyContentHandler(WRITE_LIMIT);
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            new AutoDetectParser().parse(input, handler, metadata, new org.apache.tika.parser.ParseContext());
        } catch (IOException | SAXException | TikaException e) {
            throw new ParseException("解析文档失败: " + readable(e), e);
        }

        String plainText = normalize(handler.toString()).strip();
        if (plainText.isBlank()) {
            throw new ParseException("未从文档中提取到可用文本");
        }

        List<ParsedBlock> blocks = splitBlocks(plainText);
        Map<String, byte[]> derivedFiles = new LinkedHashMap<>();
        derivedFiles.put("full.md", plainText.getBytes(StandardCharsets.UTF_8));
        derivedFiles.put("content.json", writeJson(blocks));

        return new ParsedDocument(plainText, blocks, 1, engine(), engineVersion(), derivedFiles);
    }

    private long maxBytes() {
        int megabytes = Math.max(1, properties.getParser().getMaxFileSizeMb());
        return (long) megabytes * 1024L * 1024L;
    }

    private List<ParsedBlock> splitBlocks(String plainText) {
        List<ParsedBlock> blocks = new ArrayList<>();
        int length = plainText.length();
        int index = 0;
        int counter = 0;
        while (index < length) {
            while (index < length && plainText.charAt(index) == '\n') {
                index++;
            }
            if (index >= length) {
                break;
            }
            int lineEnd = plainText.indexOf('\n', index);
            if (lineEnd < 0) {
                lineEnd = length;
            }
            int start = index;
            int end = lineEnd;
            while (start < end && Character.isWhitespace(plainText.charAt(start))) {
                start++;
            }
            while (end > start && Character.isWhitespace(plainText.charAt(end - 1))) {
                end--;
            }
            if (start < end) {
                String text = plainText.substring(start, end);
                blocks.add(new ParsedBlock(
                        "tika-" + counter,
                        isTitle(text) ? "title" : "text",
                        text,
                        1,
                        null,
                        start,
                        end,
                        null));
                counter++;
            }
            index = lineEnd + 1;
        }
        return List.copyOf(blocks);
    }

    private static boolean isTitle(String text) {
        if (text.length() > 60 || text.indexOf('\n') >= 0) {
            return false;
        }
        return SENTENCE_END.indexOf(text.charAt(text.length() - 1)) < 0;
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private byte[] writeJson(List<ParsedBlock> blocks) {
        try {
            return objectMapper.writeValueAsBytes(blocks);
        } catch (JsonProcessingException e) {
            throw new ParseException("序列化解析结果失败", e);
        }
    }

    private static String readable(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String resolveTikaVersion() {
        Package tikaPackage = TikaConfig.class.getPackage();
        if (tikaPackage != null) {
            String version = tikaPackage.getImplementationVersion();
            if (version != null && !version.isBlank()) {
                return version;
            }
        }
        return FALLBACK_VERSION;
    }
}
