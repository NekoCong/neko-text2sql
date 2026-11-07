package com.nekocong.text2sql.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.config.KnowledgeProperties;
import com.nekocong.text2sql.knowledge.KnowledgeDocument;
import com.nekocong.text2sql.knowledge.KnowledgeItem;
import com.nekocong.text2sql.knowledge.KnowledgeScope;
import com.nekocong.text2sql.knowledge.KnowledgeType;
import com.nekocong.text2sql.knowledge.api.dto.ContentBlockView;
import com.nekocong.text2sql.knowledge.api.dto.DocumentContentView;
import com.nekocong.text2sql.knowledge.api.dto.PreviewUrlView;
import com.nekocong.text2sql.knowledge.repo.KnowledgeRepository;
import com.nekocong.text2sql.knowledge.store.ObjectStore;
import com.nekocong.text2sql.knowledge.store.ObjectStoreException;
import com.nekocong.text2sql.security.DataScope;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Builds the original-source preview: a short-lived presigned URL plus the normalized text snapshot
 * the client highlights.
 *
 * <p>The snapshot is read from the derived artifacts written by the parser ({@code full.md} and
 * {@code content.json}); the character offsets in those blocks are the same offsets the chunks carry,
 * so a citation range can be highlighted directly.
 */
@Service
@ConditionalOnProperty(prefix = "text2sql.knowledge", name = "enabled", havingValue = "true")
public class KnowledgePreviewService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgePreviewService.class);

    private final KnowledgeRepository repository;
    private final ObjectStore objectStore;
    private final KnowledgeProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service.
     *
     * @param repository the knowledge repository
     * @param objectStore the object store
     * @param properties the knowledge configuration
     * @param objectMapper the shared Jackson mapper
     */
    public KnowledgePreviewService(
            KnowledgeRepository repository,
            ObjectStore objectStore,
            KnowledgeProperties properties,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectStore = objectStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Signs a preview URL for a document's original object, after checking the caller may see it.
     *
     * @param documentId the document id
     * @param scope the caller's data scope
     * @return the URL and its expiry
     * @throws NoSuchElementException when the document does not exist
     * @throws ResponseStatusException with {@code 403} when the document is out of the caller's scope
     */
    public PreviewUrlView previewUrl(String documentId, DataScope scope) {
        KnowledgeDocument document = requireDocument(documentId);
        requireVisible(document, scope);
        Duration ttl = Duration.ofSeconds(properties.getMinio().getPresignTtlSeconds());
        String url = objectStore.presignGet(document.objectKey(), ttl);
        return new PreviewUrlView(url, Instant.now().plus(ttl));
    }

    /**
     * Reads the normalized text snapshot of a document, after checking the caller may see it.
     *
     * @param documentId the document id
     * @param scope the caller's data scope
     * @return the content
     * @throws NoSuchElementException when the document or its derived artifacts are missing
     * @throws ResponseStatusException with {@code 403} when the document is out of the caller's scope
     */
    public DocumentContentView content(String documentId, DataScope scope) {
        KnowledgeDocument document = requireDocument(documentId);
        requireVisible(document, scope);
        return readContent(document);
    }

    /**
     * Enforces the document visibility rule: metadata has no preview, datasource-scoped business
     * knowledge requires access to that datasource, global business and agent knowledge are visible
     * to any authenticated caller (agent knowledge is only reachable through citations the caller's
     * own answer produced, and document ids are unguessable UUIDs).
     */
    private void requireVisible(KnowledgeDocument document, DataScope scope) {
        if (scope == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该知识原文");
        }
        KnowledgeItem item = repository
                .findItem(document.itemId())
                .orElseThrow(() -> new NoSuchElementException("知识条目不存在：" + document.itemId()));
        if (item.knowledgeType() == KnowledgeType.METADATA) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该知识原文");
        }
        if (item.knowledgeType() == KnowledgeType.BUSINESS
                && item.scope() == KnowledgeScope.DATASOURCE
                && !scope.isAdmin()
                && (item.datasourceId() == null
                        || !scope.allowedDatasources().contains(item.datasourceId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该数据源的知识原文");
        }
    }

    /**
     * Reads the original bytes of a document, after checking the caller may see it. The browser
     * fetches this from the same origin with its bearer token, so the preview never depends on a
     * presigned URL, CORS configuration or an externally reachable object store.
     *
     * @param documentId the document id
     * @param scope the caller's data scope
     * @return the bytes and their media type
     * @throws NoSuchElementException when the document does not exist
     */
    public FileContent file(String documentId, DataScope scope) {
        KnowledgeDocument document = requireDocument(documentId);
        requireVisible(document, scope);
        byte[] bytes = objectStore.get(document.objectKey());
        String contentType = document.mimeType() == null || document.mimeType().isBlank()
                ? "application/octet-stream"
                : document.mimeType();
        return new FileContent(bytes, contentType, document.filename());
    }

    /**
     * An original document's bytes.
     *
     * @param bytes the payload
     * @param contentType the media type
     * @param filename the original filename
     */
    public record FileContent(byte[] bytes, String contentType, String filename) {
    }

    private DocumentContentView readContent(KnowledgeDocument document) {
        String prefix = document.derivedPrefix() == null
                ? "derived/" + document.id()
                : document.derivedPrefix();
        String plainText = readText(prefix + "/full.md");
        List<ContentBlockView> blocks = readBlocks(prefix + "/content.json", plainText);
        if (plainText == null || plainText.isBlank()) {
            plainText = join(blocks);
        }
        if ((plainText == null || plainText.isBlank()) && blocks.isEmpty()) {
            throw new NoSuchElementException("文档尚未解析完成，暂无原文快照：" + document.id());
        }
        int pageCount = document.pageCount() == null ? 1 : Math.max(1, document.pageCount());
        return new DocumentContentView(
                document.id(), plainText == null ? "" : plainText, pageCount, blocks);
    }

    private KnowledgeDocument requireDocument(String documentId) {
        return repository
                .findDocument(documentId)
                .orElseThrow(() -> new NoSuchElementException("文档不存在：" + documentId));
    }

    private String readText(String key) {
        try {
            return new String(objectStore.get(key), StandardCharsets.UTF_8);
        } catch (ObjectStoreException ex) {
            return null;
        }
    }

    private List<ContentBlockView> readBlocks(String key, String plainText) {
        byte[] bytes;
        try {
            bytes = objectStore.get(key);
        } catch (ObjectStoreException ex) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(bytes);
            JsonNode array = root.isArray() ? root : root.path("blocks");
            if (!array.isArray()) {
                return List.of();
            }
            List<ContentBlockView> blocks = new ArrayList<>();
            int index = 0;
            for (JsonNode node : array) {
                String text = text(node, "text", "content");
                if (text == null) {
                    text = "";
                }
                Integer pageNo = integer(node, "pageNo", "page_no", "pageIdx", "page_idx");
                if (pageNo != null && !has(node, "pageNo", "page_no")) {
                    pageNo = pageNo + 1;
                }
                blocks.add(new ContentBlockView(
                        text(node, "blockId", "block_id", "id"),
                        text(node, "type", "blockType"),
                        text,
                        pageNo == null ? 1 : pageNo,
                        bbox(node),
                        integer(node, "charStart", "char_start"),
                        integer(node, "charEnd", "char_end"),
                        text(node, "imagePath", "image_path", "img_path")));
                index++;
            }
            return normalizeOffsets(blocks, plainText);
        } catch (Exception ex) {
            log.warn("解析 content.json 失败，改用全文快照：{}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * Keeps the parser's offsets when they slice back to the block text; otherwise rebuilds them
     * sequentially against the returned snapshot so the client can still highlight reliably.
     */
    private static List<ContentBlockView> normalizeOffsets(
            List<ContentBlockView> blocks, String plainText) {
        boolean usable = plainText != null && !plainText.isEmpty();
        if (usable) {
            boolean allValid = true;
            for (ContentBlockView block : blocks) {
                if (block.charStart() == null
                        || block.charEnd() == null
                        || block.charStart() < 0
                        || block.charEnd() > plainText.length()
                        || block.charStart() >= block.charEnd()) {
                    allValid = false;
                    break;
                }
            }
            if (allValid) {
                return blocks;
            }
        }
        List<ContentBlockView> rebuilt = new ArrayList<>(blocks.size());
        int cursor = 0;
        for (ContentBlockView block : blocks) {
            String text = block.text() == null ? "" : block.text();
            rebuilt.add(new ContentBlockView(
                    block.blockId(),
                    block.type(),
                    text,
                    block.pageNo(),
                    block.bbox(),
                    cursor,
                    cursor + text.length(),
                    block.imagePath()));
            cursor += text.length() + 1;
        }
        return rebuilt;
    }

    private static String join(List<ContentBlockView> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlockView block : blocks) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(block.text() == null ? "" : block.text());
        }
        return sb.toString();
    }

    private static boolean has(JsonNode node, String... names) {
        for (String name : names) {
            if (node.hasNonNull(name)) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private static Integer integer(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) {
                return value.asInt();
            }
        }
        return null;
    }

    private static List<Integer> bbox(JsonNode node) {
        JsonNode value = node.get("bbox");
        if (value == null || !value.isArray() || value.size() < 4) {
            return null;
        }
        List<Integer> box = new ArrayList<>(4);
        for (JsonNode element : value) {
            box.add(element.asInt());
        }
        return box;
    }
}
