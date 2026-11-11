package com.nekocong.text2sql.knowledge.service;

import com.nekocong.text2sql.config.KnowledgeProperties;
import com.nekocong.text2sql.config.RagProperties;
import com.nekocong.text2sql.knowledge.FailureStage;
import com.nekocong.text2sql.knowledge.KnowledgeChunk;
import com.nekocong.text2sql.knowledge.KnowledgeDocument;
import com.nekocong.text2sql.knowledge.KnowledgeItem;
import com.nekocong.text2sql.knowledge.KnowledgeScope;
import com.nekocong.text2sql.knowledge.KnowledgeStatus;
import com.nekocong.text2sql.knowledge.KnowledgeType;
import com.nekocong.text2sql.knowledge.ParseStatus;
import com.nekocong.text2sql.knowledge.SourceType;
import com.nekocong.text2sql.knowledge.parse.DocumentParser;
import com.nekocong.text2sql.knowledge.parse.DocumentParserSelector;
import com.nekocong.text2sql.knowledge.parse.ParseContext;
import com.nekocong.text2sql.knowledge.parse.ParsedDocument;
import com.nekocong.text2sql.knowledge.parse.UrlDocumentFetcher;
import com.nekocong.text2sql.knowledge.repo.KnowledgeRepository;
import com.nekocong.text2sql.knowledge.store.ObjectStore;
import com.nekocong.text2sql.knowledge.store.VectorStore;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The knowledge ingestion lifecycle: persist → parse → chunk → embed → ready.
 *
 * <p>Writes commit first and only then hand the item to an async worker, so an upload request is
 * never blocked by MinerU, Tika or an embedding API. Failures keep their stage, so a retry resumes
 * at the cheapest possible point (an {@code EMBED} failure does not re-spend a MinerU call).
 */
@Service
@ConditionalOnProperty(prefix = "text2sql.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private static final int EMBEDDING_DIMENSIONS = 1024;

    private final KnowledgeRepository repository;
    private final VectorStore vectorStore;
    private final ObjectStore objectStore;
    private final ChunkingService chunking;
    private final DocumentParserSelector parserSelector;
    private final UrlDocumentFetcher urlFetcher;
    private final KnowledgeProperties properties;
    private final RagProperties ragProperties;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<EmbeddingModel> embeddings;
    private final Executor executor;

    /**
     * Creates the service.
     *
     * @param repository the knowledge repository
     * @param vectorStore the chunk/vector store
     * @param objectStore the object store
     * @param chunking the chunker
     * @param parserSelector the parser selector
     * @param urlFetcher the URL fetcher
     * @param properties the knowledge configuration
     * @param ragProperties the embedding configuration
     * @param events the event publisher
     * @param embeddings the optional embedding model
     * @param executor the async ingestion executor
     */
    public KnowledgeIngestionService(
            KnowledgeRepository repository,
            VectorStore vectorStore,
            ObjectStore objectStore,
            ChunkingService chunking,
            DocumentParserSelector parserSelector,
            UrlDocumentFetcher urlFetcher,
            KnowledgeProperties properties,
            RagProperties ragProperties,
            ApplicationEventPublisher events,
            ObjectProvider<EmbeddingModel> embeddings,
            @Qualifier("knowledgeExecutor") Executor executor) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.objectStore = objectStore;
        this.chunking = chunking;
        this.parserSelector = parserSelector;
        this.urlFetcher = urlFetcher;
        this.properties = properties;
        this.ragProperties = ragProperties;
        this.events = events;
        this.embeddings = embeddings;
        this.executor = executor;
    }

    /**
     * Creates or replaces a text-only item and schedules its chunking/embedding.
     *
     * @param draft the item
     * @return the persisted item
     */
    @Transactional("knowledgeTransactionManager")
    public KnowledgeItem saveTextItem(KnowledgeItemDraft draft) {
        validateTextDraft(draft);
        Instant now = Instant.now();
        KnowledgeItem item = buildItem(draft, null, draft.content(), now);
        KnowledgeItem stored = repository.upsertItem(item);
        events.publishEvent(KnowledgeItemSavedEvent.full(stored.id()));
        return stored;
    }

    /**
     * Updates the text of an existing text-only item, keeping its id, key and owner.
     *
     * @param id the item id
     * @param draft the new title/content
     * @return the persisted item
     */
    @Transactional("knowledgeTransactionManager")
    public KnowledgeItem updateTextItem(String id, KnowledgeItemDraft draft) {
        KnowledgeItem existing = repository
                .findItem(id)
                .orElseThrow(() -> new NoSuchElementException("知识条目不存在：" + id));
        if (existing.knowledgeType() == KnowledgeType.METADATA) {
            throw new IllegalArgumentException("元数据知识由元数据同步维护，不能手工编辑");
        }
        if (repository.findDocumentByItem(id).isPresent()) {
            throw new IllegalArgumentException("文档型知识不能改为文本内容，请删除后重新上传");
        }
        if (draft == null || draft.content() == null || draft.content().isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (ownerChanged(existing, draft)) {
            throw new IllegalArgumentException("不能修改知识归属，请删除后重建");
        }
        Instant now = Instant.now();
        String title = draft.title() == null || draft.title().isBlank()
                ? existing.title()
                : draft.title().strip();
        KnowledgeItem updated = new KnowledgeItem(
                existing.id(),
                existing.knowledgeType(),
                existing.scope(),
                existing.datasourceId(),
                existing.agentId(),
                existing.itemKey(),
                title,
                existing.tableName(),
                draft.content(),
                KnowledgeStatus.PENDING,
                null,
                null,
                existing.embeddingModel(),
                existing.createdAt(),
                now,
                null);
        KnowledgeItem stored = repository.upsertItem(updated);
        events.publishEvent(KnowledgeItemSavedEvent.full(stored.id()));
        return stored;
    }

    private static boolean ownerChanged(KnowledgeItem existing, KnowledgeItemDraft draft) {
        if (draft.scope() != null && draft.scope() != existing.scope()) {
            return true;
        }
        return !java.util.Objects.equals(existing.datasourceId(), blankToNull(draft.datasourceId()))
                || !java.util.Objects.equals(existing.agentId(), blankToNull(draft.agentId()));
    }

    /**
     * Stores an uploaded document and schedules parsing.
     *
     * @param draft the owning item (BUSINESS or AGENT)
     * @param filename the original filename
     * @param mimeType the media type
     * @param bytes the bytes
     * @return the persisted document
     */
    @Transactional("knowledgeTransactionManager")
    public KnowledgeDocument upload(
            KnowledgeItemDraft draft, String filename, String mimeType, byte[] bytes) {
        validateDocumentDraft(draft);
        long maxBytes = (long) properties.getParser().getMaxFileSizeMb() * 1024L * 1024L;
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("上传文件为空");
        }
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException(
                    "文件超过大小上限 " + properties.getParser().getMaxFileSizeMb() + "MB");
        }
        String sha256 = sha256(bytes);
        Optional<KnowledgeDocument> duplicate = repository.findDocumentBySha(sha256);
        if (duplicate.isPresent() && sameOwner(draft, duplicate.get())) {
            return duplicate.get();
        }

        Instant now = Instant.now();
        String documentId = UUID.randomUUID().toString();
        String safeName = safeFilename(filename);
        String itemKey = resolveItemKey(draft, safeName);
        KnowledgeItem item = buildItem(draft, itemKey, null, now);
        KnowledgeItem stored = repository.upsertItem(item);

        String objectKey =
                "originals/" + ownerSegment(draft) + "/" + stored.id() + "/" + documentId + "/" + safeName;
        objectStore.put(objectKey, bytes, mimeType);
        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                stored.id(),
                SourceType.UPLOAD,
                null,
                safeName,
                mimeType,
                bytes.length,
                sha256,
                objectKey,
                "derived/" + documentId,
                null,
                null,
                null,
                ParseStatus.PENDING,
                null,
                null,
                now);
        KnowledgeDocument persisted = repository.upsertDocument(document);
        events.publishEvent(KnowledgeItemSavedEvent.full(stored.id()));
        return persisted;
    }

    /**
     * Fetches a web page, stores the snapshot and schedules parsing.
     *
     * @param draft the owning item (BUSINESS or AGENT)
     * @param url the page URL
     * @return the persisted document
     */
    @Transactional("knowledgeTransactionManager")
    public KnowledgeDocument importUrl(KnowledgeItemDraft draft, String url) {
        validateDocumentDraft(draft);
        UrlDocumentFetcher.FetchedPage page = urlFetcher.fetch(url);
        String sha256 = sha256(page.body());
        Instant now = Instant.now();
        String documentId = UUID.randomUUID().toString();
        String filename = filenameFromUrl(page.finalUrl());
        String mimeType = page.contentType() == null ? "text/html" : page.contentType();
        String itemKey = resolveItemKey(draft, filename);
        KnowledgeItem item = buildItem(draft, itemKey, null, now);
        KnowledgeItem stored = repository.upsertItem(item);

        String objectKey =
                "originals/" + ownerSegment(draft) + "/" + stored.id() + "/" + documentId + "/" + filename;
        objectStore.put(objectKey, page.body(), mimeType);
        objectStore.put(
                "snapshots/" + documentId + "/source.html", page.body(), mimeType);
        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                stored.id(),
                SourceType.URL,
                page.finalUrl(),
                filename,
                mimeType,
                page.body().length,
                sha256,
                objectKey,
                "derived/" + documentId,
                null,
                null,
                null,
                ParseStatus.PENDING,
                null,
                page.fetchedAt(),
                now);
        KnowledgeDocument persisted = repository.upsertDocument(document);
        events.publishEvent(KnowledgeItemSavedEvent.full(stored.id()));
        return persisted;
    }

    /**
     * Retries a failed item from its recorded failure stage.
     *
     * @param itemId the item id
     * @return the item, now {@code PENDING}
     */
    @Transactional("knowledgeTransactionManager")
    public KnowledgeItem retry(String itemId) {
        KnowledgeItem item = repository
                .findItem(itemId)
                .orElseThrow(() -> new NoSuchElementException("知识条目不存在：" + itemId));
        FailureStage resume = item.failureStage();
        repository.updateItemStatus(itemId, KnowledgeStatus.PENDING, null, null, item.embeddingModel());
        events.publishEvent(new KnowledgeItemSavedEvent(itemId, resume, null));
        return repository.findItem(itemId).orElse(item);
    }

    /**
     * Re-parses a document with an explicit engine, replacing its chunks and vectors.
     *
     * @param documentId the document id
     * @param engine the engine id ({@code tika}/{@code mineru}), or {@code null} for the configured one
     * @return the document, now {@code PENDING}
     */
    @Transactional("knowledgeTransactionManager")
    public KnowledgeDocument reparse(String documentId, String engine) {
        KnowledgeDocument document = repository
                .findDocument(documentId)
                .orElseThrow(() -> new NoSuchElementException("文档不存在：" + documentId));
        vectorStore.deleteByItem(document.itemId());
        repository.updateItemStatus(document.itemId(), KnowledgeStatus.PENDING, null, null, null);
        repository.updateDocumentParse(documentId, ParseStatus.PENDING, null, null, null, null);
        events.publishEvent(new KnowledgeItemSavedEvent(document.itemId(), FailureStage.PARSE, engine));
        return repository.findDocument(documentId).orElse(document);
    }

    /**
     * Deletes an item with its chunks, vectors and objects.
     *
     * @param itemId the item id
     */
    @Transactional("knowledgeTransactionManager")
    public void deleteItem(String itemId) {
        repository.findDocumentByItem(itemId).ifPresent(this::deleteDocumentObjects);
        vectorStore.deleteByItem(itemId);
        repository.findDocumentByItem(itemId).ifPresent(document -> repository.deleteDocument(document.id()));
        repository.deleteItem(itemId);
    }

    /**
     * Runs the ingestion pipeline for one item. Public so a compensation scan can reuse it.
     *
     * @param itemId the item id
     * @param resumeFrom the stage to resume from, or {@code null}
     * @param engineOverride an explicit parser engine, or {@code null}
     */
    public void process(String itemId, FailureStage resumeFrom, String engineOverride) {
        Optional<KnowledgeItem> maybe = repository.findItem(itemId);
        if (maybe.isEmpty()) {
            return;
        }
        KnowledgeItem item = maybe.get();
        KnowledgeDocument document = repository.findDocumentByItem(itemId).orElse(null);
        FailureStage stage = resumeFrom == null ? FailureStage.PARSE : resumeFrom;
        try {
            List<KnowledgeChunk> chunks = null;
            if (stage == FailureStage.EMBED) {
                chunks = vectorStore.listByItem(itemId);
                if (chunks.isEmpty()) {
                    stage = FailureStage.PARSE;
                }
            }
            if (stage != FailureStage.EMBED) {
                if (document != null) {
                    repository.updateItemStatus(itemId, KnowledgeStatus.PARSING, null, null, null);
                    repository.updateDocumentParse(
                            document.id(), ParseStatus.PARSING, null, null, null, null);
                    stage = FailureStage.PARSE;
                    ParsedDocument parsed = parse(document, engineOverride);
                    storeDerived(document, parsed);
                    repository.updateDocumentParse(
                            document.id(),
                            ParseStatus.PARSED,
                            parsed.engine(),
                            parsed.engineVersion(),
                            parsed.pageCount(),
                            null);
                    stage = FailureStage.CHUNK;
                    chunks = chunking.chunk(
                            itemId, document.id(), parsed, properties.getChunkSize());
                } else {
                    stage = FailureStage.CHUNK;
                    chunks = chunking.chunkText(itemId, item.content(), properties.getChunkSize());
                }
            }

            stage = FailureStage.EMBED;
            repository.updateItemStatus(itemId, KnowledgeStatus.EMBEDDING, null, null, null);
            List<KnowledgeChunk> embedded = embed(chunks);
            vectorStore.replaceChunks(item, embedded);
            repository.updateItemStatus(
                    itemId, KnowledgeStatus.READY, null, null, embeddingModelName());
            log.info("知识条目已就绪：id={}, chunks={}", itemId, embedded.size());
        } catch (RuntimeException ex) {
            String reason = readable(ex);
            log.warn("知识条目处理失败：id={}, stage={}, reason={}", itemId, stage, reason);
            repository.updateItemStatus(itemId, KnowledgeStatus.FAILED, stage.name(), reason, null);
            if (document != null && stage == FailureStage.PARSE) {
                repository.updateDocumentParse(
                        document.id(), ParseStatus.FAILED, null, null, null, reason);
            }
        }
    }

    /**
     * The transactional-after-commit entry point that starts the async worker.
     *
     * @param event the saved event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSaved(KnowledgeItemSavedEvent event) {
        executor.execute(() -> process(event.itemId(), event.resumeFrom(), event.engineOverride()));
    }

    /**
     * Lists items.
     *
     * @param type optional type filter
     * @param datasourceId optional datasource filter
     * @param agentId optional agent filter
     * @param status optional status filter
     * @param limit maximum rows
     * @return the items
     */
    public List<KnowledgeItem> items(
            KnowledgeType type, String datasourceId, String agentId, KnowledgeStatus status, int limit) {
        return repository.listItems(type, datasourceId, agentId, status, limit);
    }

    /**
     * Lists documents.
     *
     * @param type optional type filter
     * @param datasourceId optional datasource filter
     * @param agentId optional agent filter
     * @param status optional item-status filter
     * @param limit maximum rows
     * @return the documents
     */
    public List<KnowledgeDocument> documents(
            KnowledgeType type, String datasourceId, String agentId, KnowledgeStatus status, int limit) {
        return repository.listDocuments(type, datasourceId, agentId, status, limit);
    }

    /**
     * Loads one item.
     *
     * @param id the item id
     * @return the item
     */
    public KnowledgeItem requireItem(String id) {
        return repository
                .findItem(id)
                .orElseThrow(() -> new NoSuchElementException("知识条目不存在：" + id));
    }

    /**
     * Loads one document.
     *
     * @param id the document id
     * @return the document
     */
    public KnowledgeDocument requireDocument(String id) {
        return repository
                .findDocument(id)
                .orElseThrow(() -> new NoSuchElementException("文档不存在：" + id));
    }

    private ParsedDocument parse(KnowledgeDocument document, String engineOverride) {
        byte[] bytes = objectStore.get(document.objectKey());
        ParseContext context = new ParseContext(
                document.filename(), document.mimeType(), bytes, document.sourceUrl());
        DocumentParser parser = engineOverride == null
                ? parserSelector.select(context)
                : parserSelector.select(engineOverride, context);
        return parser.parse(context);
    }

    private void storeDerived(KnowledgeDocument document, ParsedDocument parsed) {
        String prefix = document.derivedPrefix() == null
                ? "derived/" + document.id()
                : document.derivedPrefix();
        for (Map.Entry<String, byte[]> entry : parsed.derivedFiles().entrySet()) {
            objectStore.put(prefix + "/" + entry.getKey(), entry.getValue(), "application/octet-stream");
        }
    }

    private List<KnowledgeChunk> embed(List<KnowledgeChunk> chunks) {
        EmbeddingModel model = embeddings.getIfAvailable();
        if (model == null) {
            if (properties.isLexicalFallback()) {
                return chunks;
            }
            throw new IllegalStateException("未配置 embedding 模型，无法生成向量");
        }
        List<KnowledgeChunk> embedded = new ArrayList<>(chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            double[] vector;
            try {
                vector = model.embed(TextBlock.builder().text(chunk.content()).build()).block();
            } catch (RuntimeException ex) {
                throw new IllegalStateException("向量生成失败：" + readable(ex), ex);
            }
            if (vector == null || vector.length != EMBEDDING_DIMENSIONS) {
                throw new IllegalStateException(
                        "向量维度不符合预期（期望 " + EMBEDDING_DIMENSIONS + "）");
            }
            embedded.add(new KnowledgeChunk(
                    chunk.id(),
                    chunk.itemId(),
                    chunk.documentId(),
                    chunk.chunkIndex(),
                    chunk.content(),
                    chunk.pageNo(),
                    chunk.bbox(),
                    chunk.charStart(),
                    chunk.charEnd(),
                    chunk.quote(),
                    vector));
        }
        return embedded;
    }

    private String embeddingModelName() {
        return embeddings.getIfAvailable() == null ? null : ragProperties.getEmbeddingModel();
    }

    private void deleteDocumentObjects(KnowledgeDocument document) {
        try {
            objectStore.delete(document.objectKey());
            if (document.derivedPrefix() != null) {
                objectStore.deletePrefix(document.derivedPrefix() + "/");
            }
            objectStore.deletePrefix("snapshots/" + document.id() + "/");
        } catch (RuntimeException ex) {
            log.warn("删除文档对象失败（继续删除数据库记录）：{}", ex.getMessage());
        }
    }

    private KnowledgeItem buildItem(
            KnowledgeItemDraft draft, String itemKey, String content, Instant now) {
        KnowledgeType type = draft.knowledgeType();
        KnowledgeScope scope = resolveScope(draft);
        String key = itemKey == null ? resolveItemKey(draft, draft.title()) : itemKey;
        String title = (draft.title() == null || draft.title().isBlank()) ? key : draft.title().strip();
        return new KnowledgeItem(
                UUID.randomUUID().toString(),
                type,
                scope,
                blankToNull(draft.datasourceId()),
                blankToNull(draft.agentId()),
                key,
                title,
                blankToNull(draft.tableName()),
                content,
                KnowledgeStatus.PENDING,
                null,
                null,
                null,
                now,
                now,
                null);
    }

    private static void validateTextDraft(KnowledgeItemDraft draft) {
        if (draft == null || draft.knowledgeType() == null) {
            throw new IllegalArgumentException("knowledgeType 不能为空");
        }
        if (draft.knowledgeType() == KnowledgeType.METADATA) {
            throw new IllegalArgumentException("元数据知识由元数据同步生成，不能手工写入");
        }
        if (draft.content() == null || draft.content().isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        validateOwner(draft);
    }

    private static void validateDocumentDraft(KnowledgeItemDraft draft) {
        if (draft == null || draft.knowledgeType() == null) {
            throw new IllegalArgumentException("knowledgeType 不能为空");
        }
        if (draft.knowledgeType() == KnowledgeType.METADATA) {
            throw new IllegalArgumentException("元数据知识不支持文档上传");
        }
        validateOwner(draft);
    }

    private static void validateOwner(KnowledgeItemDraft draft) {
        if (draft.knowledgeType() == KnowledgeType.AGENT
                && (draft.agentId() == null || draft.agentId().isBlank())) {
            throw new IllegalArgumentException("智能体知识必须指定 agentId");
        }
        if (draft.knowledgeType() == KnowledgeType.BUSINESS
                && draft.datasourceId() != null
                && draft.datasourceId().isBlank()) {
            throw new IllegalArgumentException("datasourceId 不能为空字符串");
        }
    }

    private static KnowledgeScope resolveScope(KnowledgeItemDraft draft) {
        if (draft.scope() != null) {
            return draft.scope();
        }
        return switch (draft.knowledgeType()) {
            case METADATA -> KnowledgeScope.DATASOURCE;
            case AGENT -> KnowledgeScope.AGENT;
            case BUSINESS -> draft.datasourceId() == null || draft.datasourceId().isBlank()
                    ? KnowledgeScope.GLOBAL
                    : KnowledgeScope.DATASOURCE;
        };
    }

    private static String resolveItemKey(KnowledgeItemDraft draft, String fallbackName) {
        if (draft.itemKey() != null && !draft.itemKey().isBlank()) {
            return draft.itemKey().strip();
        }
        String owner = switch (resolveScope(draft)) {
            case GLOBAL -> "global";
            case DATASOURCE -> safeSegment(draft.datasourceId());
            case AGENT -> safeSegment(draft.agentId());
        };
        String name = draft.tableName() != null && !draft.tableName().isBlank()
                ? draft.tableName()
                : fallbackName;
        return draft.knowledgeType().name() + ":" + owner + ":" + safeSegment(name);
    }

    private boolean sameOwner(KnowledgeItemDraft draft, KnowledgeDocument document) {
        return repository
                .findItem(document.itemId())
                .map(item -> item.knowledgeType() == draft.knowledgeType()
                        && java.util.Objects.equals(
                                item.datasourceId(), blankToNull(draft.datasourceId()))
                        && java.util.Objects.equals(item.agentId(), blankToNull(draft.agentId())))
                .orElse(false);
    }

    private static String ownerSegment(KnowledgeItemDraft draft) {
        return switch (resolveScope(draft)) {
            case GLOBAL -> "global";
            case DATASOURCE -> safeSegment(draft.datasourceId());
            case AGENT -> safeSegment(draft.agentId());
        };
    }

    private static String safeSegment(String value) {
        String text = value == null || value.isBlank() ? "default" : value.strip();
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fff._-]+", "-");
    }

    private static String safeFilename(String filename) {
        String name = filename == null || filename.isBlank() ? "document" : filename.strip();
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.replaceAll("[\\p{Cntrl}]", "_");
    }

    private static String filenameFromUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath();
            if (path != null && !path.isBlank() && !path.endsWith("/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isBlank()) {
                    return name;
                }
            }
            return (uri.getHost() == null ? "page" : uri.getHost()) + ".html";
        } catch (IllegalArgumentException ex) {
            return "page.html";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String readable(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** @return the UTF-8 bytes of a string (used by tests) */
    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
