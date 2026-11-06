package com.nekocong.text2sql.knowledge.parse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.config.KnowledgeProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU-backed parser for PDFs and images.
 *
 * <p>The client returns a zip containing {@code full.md}, a content list with per-element page and
 * bbox data, and extracted images. The content list is mapped to {@link ParsedBlock}s: {@code
 * page_idx + 1} becomes the 1-based page number and the normalized 0–1000 bbox is preserved so the
 * frontend can highlight the original region.
 */
public class MinerUDocumentParser implements DocumentParser {

    private static final String ENGINE = "mineru";
    private static final String DEFAULT_VERSION = "v4";
    private static final int MAX_BLOCKS = 1_000_000;

    private final KnowledgeProperties properties;
    private final MinerUClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile String engineVersion = DEFAULT_VERSION;

    /**
     * Creates the parser.
     *
     * @param properties the knowledge configuration
     * @param client the MinerU client used to run the remote job
     */
    public MinerUDocumentParser(KnowledgeProperties properties, MinerUClient client) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String engine() {
        return ENGINE;
    }

    @Override
    public String engineVersion() {
        return engineVersion;
    }

    @Override
    public boolean available() {
        return client.available();
    }

    @Override
    public boolean supports(ParseContext context) {
        return context != null && (DocumentFormats.isPdf(context) || DocumentFormats.isImage(context));
    }

    @Override
    public ParsedDocument parse(ParseContext context) {
        if (!client.available()) {
            throw new ParseException(
                    "MinerU 未配置 Token，无法解析 PDF/图片；请配置 MINERU_TOKEN 或切换解析引擎");
        }
        if (context == null || context.bytes() == null || context.bytes().length == 0) {
            throw new ParseException("MinerU 解析失败：文档内容为空");
        }

        MinerUResult result = client.parse(context);
        if (result == null || result.zipBytes() == null || result.zipBytes().length == 0) {
            throw new ParseException("MinerU 返回的解析结果为空");
        }
        if (result.engineVersion() != null && !result.engineVersion().isBlank()) {
            engineVersion = result.engineVersion();
        }

        Map<String, byte[]> entries = unzip(result.zipBytes());
        byte[] fullMarkdown = findMarkdown(entries);
        byte[] contentListBytes = findContentList(entries);

        List<ParsedBlock> blocks = List.of();
        if (contentListBytes != null) {
            blocks = mapContentList(readJson(contentListBytes));
        }
        if (!hasText(blocks) && fullMarkdown != null) {
            blocks = fallbackFromMarkdown(fullMarkdown);
        }
        if (!hasText(blocks)) {
            throw new ParseException("MinerU 未返回可用的文本内容");
        }

        String plainText = joinTexts(blocks);
        int pageCount = 1;
        for (ParsedBlock block : blocks) {
            pageCount = Math.max(pageCount, block.pageNo());
        }

        Map<String, byte[]> derivedFiles = new LinkedHashMap<>();
        // full.md is the snapshot the chunk offsets refer to; the original markdown is kept aside.
        derivedFiles.put("full.md", plainText.getBytes(StandardCharsets.UTF_8));
        if (fullMarkdown != null) {
            derivedFiles.put("mineru.md", fullMarkdown);
        }
        derivedFiles.put("content.json", writeJson(blocks));
        for (ParsedBlock block : blocks) {
            String imagePath = block.imagePath();
            if (imagePath == null || imagePath.isBlank()) {
                continue;
            }
            byte[] image = findImage(entries, imagePath);
            if (image != null) {
                derivedFiles.put(normalizeEntryName(imagePath), image);
            }
        }

        return new ParsedDocument(plainText, blocks, pageCount, engine(), engineVersion(), derivedFiles);
    }

    /**
     * Maps a MinerU content-list JSON array to parsed blocks.
     *
     * <p>Elements without a four-value bbox are ignored. The returned {@code charStart}/{@code
     * charEnd} offsets refer to the text produced by joining the block texts with {@code "\n"}, so
     * callers can slice the text back exactly. This helper is public so the mapping can be tested
     * without any network access.
     *
     * @param contentList the content-list JSON (an array, or an object containing one)
     * @return the mapped blocks in document order
     */
    public static List<ParsedBlock> mapContentList(JsonNode contentList) {
        if (contentList == null || contentList.isNull()) {
            return List.of();
        }
        JsonNode array = contentList;
        if (!contentList.isArray()) {
            JsonNode nested = contentList.get("content_list");
            if (nested == null) {
                nested = contentList.get("blocks");
            }
            array = nested;
        }
        if (array == null || !array.isArray()) {
            return List.of();
        }

        List<String> texts = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<Integer> pages = new ArrayList<>();
        List<int[]> boxes = new ArrayList<>();
        List<String> imagePaths = new ArrayList<>();

        for (JsonNode element : array) {
            if (element == null || !element.isObject()) {
                continue;
            }
            int[] bbox = readBbox(element.get("bbox"));
            if (bbox == null) {
                continue;
            }
            String type = element.path("type").asText("text");
            String text = extractText(element, type);
            int pageNo = Math.max(1, element.path("page_idx").asInt(0) + 1);
            String imagePath = element.hasNonNull("img_path") ? element.get("img_path").asText() : null;

            texts.add(text);
            types.add(type);
            pages.add(pageNo);
            boxes.add(bbox);
            imagePaths.add(imagePath);
            if (texts.size() > MAX_BLOCKS) {
                throw new ParseException("MinerU content_list 元素过多，无法处理");
            }
        }

        List<ParsedBlock> blocks = new ArrayList<>(texts.size());
        int offset = 0;
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            int start = offset;
            int end = start + text.length();
            blocks.add(new ParsedBlock(
                    "mineru-" + i,
                    types.get(i),
                    text,
                    pages.get(i),
                    boxes.get(i),
                    start,
                    end,
                    imagePaths.get(i)));
            offset = end + 1;
        }
        return List.copyOf(blocks);
    }

    private List<ParsedBlock> fallbackFromMarkdown(byte[] markdown) {
        String text = new String(markdown, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(new ParsedBlock("mineru-0", "text", text, 1, null, 0, text.length(), null));
    }

    private static String extractText(JsonNode element, String type) {
        if ("table".equalsIgnoreCase(type)) {
            String tableBody = element.path("table_body").asText("");
            if (!tableBody.isBlank()) {
                return tableBody;
            }
        }
        String text = element.path("text").asText("");
        if (!text.isBlank()) {
            return text;
        }
        if ("image".equalsIgnoreCase(type)) {
            String caption = joinCaptions(element.get("img_caption"));
            if (!caption.isBlank()) {
                return caption;
            }
            String tableCaption = joinCaptions(element.get("table_caption"));
            if (!tableCaption.isBlank()) {
                return tableCaption;
            }
        }
        return "";
    }

    private static String joinCaptions(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : node) {
                String value = item.asText("");
                if (value.isBlank()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(value);
            }
            return builder.toString();
        }
        return node.asText("");
    }

    private static int[] readBbox(JsonNode node) {
        if (node == null || !node.isArray() || node.size() < 4) {
            return null;
        }
        int[] bbox = new int[4];
        for (int i = 0; i < 4; i++) {
            JsonNode value = node.get(i);
            if (value == null || !value.isNumber()) {
                return null;
            }
            bbox[i] = value.asInt();
        }
        return bbox;
    }

    private static String joinTexts(List<ParsedBlock> blocks) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            String text = blocks.get(i).text();
            builder.append(text == null ? "" : text);
        }
        return builder.toString();
    }

    private static boolean hasText(List<ParsedBlock> blocks) {
        for (ParsedBlock block : blocks) {
            if (block.text() != null && !block.text().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, byte[]> unzip(byte[] zipBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        long limit = Math.max(1L, properties.getParser().getMaxFileSizeMb()) * 1024L * 1024L * 5L;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeEntryName(entry.getName());
                if (name.isEmpty()) {
                    continue;
                }
                byte[] data = zip.readAllBytes();
                total += data.length;
                if (total > limit) {
                    throw new ParseException("MinerU 返回的压缩包解压后超过大小上限");
                }
                entries.put(name, data);
            }
        } catch (IOException e) {
            throw new ParseException("解压 MinerU 结果失败", e);
        }
        return entries;
    }

    private JsonNode readJson(byte[] bytes) {
        try {
            return objectMapper.readTree(bytes);
        } catch (IOException e) {
            throw new ParseException("解析 MinerU content_list.json 失败", e);
        }
    }

    private byte[] writeJson(List<ParsedBlock> blocks) {
        try {
            return objectMapper.writeValueAsBytes(blocks);
        } catch (JsonProcessingException e) {
            throw new ParseException("序列化 MinerU 解析结果失败", e);
        }
    }

    private static byte[] findContentList(Map<String, byte[]> entries) {
        byte[] fallback = null;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!name.toLowerCase(Locale.ROOT).endsWith("content_list.json")) {
                continue;
            }
            if (name.equals("content_list.json") || name.endsWith("/content_list.json")) {
                return entry.getValue();
            }
            if (fallback == null) {
                fallback = entry.getValue();
            }
        }
        return fallback;
    }

    private static byte[] findMarkdown(Map<String, byte[]> entries) {
        byte[] full = findEntry(entries, "full.md");
        if (full != null) {
            return full;
        }
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).endsWith(".md")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static byte[] findEntry(Map<String, byte[]> entries, String name) {
        byte[] exact = entries.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().equals(name) || entry.getKey().endsWith("/" + name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static byte[] findImage(Map<String, byte[]> entries, String imagePath) {
        String normalized = normalizeEntryName(imagePath);
        if (normalized.isEmpty()) {
            return null;
        }
        byte[] exact = entries.get(normalized);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (name.endsWith("/" + normalized) || name.endsWith(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalizeEntryName(String name) {
        if (name == null) {
            return "";
        }
        String value = name.replace('\\', '/').trim();
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }
}
