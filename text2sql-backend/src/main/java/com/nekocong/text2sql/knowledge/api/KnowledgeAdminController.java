package com.nekocong.text2sql.knowledge.api;

import com.nekocong.text2sql.config.BlockingSchedulerConfig;
import com.nekocong.text2sql.knowledge.KnowledgeDocument;
import com.nekocong.text2sql.knowledge.KnowledgeItem;
import com.nekocong.text2sql.knowledge.KnowledgeStatus;
import com.nekocong.text2sql.knowledge.KnowledgeType;
import com.nekocong.text2sql.knowledge.api.dto.DocumentUrlRequest;
import com.nekocong.text2sql.knowledge.api.dto.KnowledgeDocumentView;
import com.nekocong.text2sql.knowledge.api.dto.KnowledgeItemRequest;
import com.nekocong.text2sql.knowledge.api.dto.KnowledgeItemView;
import com.nekocong.text2sql.knowledge.api.dto.MetadataSyncView;
import com.nekocong.text2sql.knowledge.api.dto.TermMappingRequest;
import com.nekocong.text2sql.knowledge.api.dto.TermMappingView;
import com.nekocong.text2sql.knowledge.repo.KnowledgeRepository;
import com.nekocong.text2sql.knowledge.service.KnowledgeIngestionService;
import com.nekocong.text2sql.knowledge.service.MetadataSyncService;
import com.nekocong.text2sql.knowledge.store.ObjectStoreException;
import com.nekocong.text2sql.knowledge.store.VectorStoreException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Admin API for the three knowledge collections: items, metadata sync, term mappings and document
 * ingestion/preview.
 *
 * <p>Every endpoint is admin-only (enforced by {@code SecurityConfig}); all blocking JDBC, MinIO and
 * parser work runs on a bounded-elastic scheduler so the event loop is never blocked.
 */
@RestController
@RequestMapping("/api/text2sql/knowledge")
@ConditionalOnProperty(prefix = "text2sql.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeAdminController {

    private static final int DEFAULT_LIMIT = 200;

    private final KnowledgeIngestionService ingestion;
    private final MetadataSyncService metadataSync;
    private final KnowledgeRepository repository;
    private final Scheduler blockingScheduler;

    /**
     * Creates the controller.
     *
     * @param ingestion the ingestion service
     * @param metadataSync the metadata sync service
     * @param repository the knowledge repository
     * @param blockingScheduler the virtual-thread scheduler for blocking work
     */
    public KnowledgeAdminController(
            KnowledgeIngestionService ingestion,
            MetadataSyncService metadataSync,
            KnowledgeRepository repository,
            @Qualifier(BlockingSchedulerConfig.BLOCKING_SCHEDULER) Scheduler blockingScheduler) {
        this.ingestion = ingestion;
        this.metadataSync = metadataSync;
        this.repository = repository;
        this.blockingScheduler = blockingScheduler;
    }

    /**
     * Lists knowledge items.
     *
     * @param type optional type filter
     * @param datasourceId optional datasource filter
     * @param agentId optional agent filter
     * @param status optional status filter
     * @param limit maximum rows
     * @return the items, newest first
     */
    @GetMapping("/items")
    public Mono<List<KnowledgeItemView>> items(
            @RequestParam(required = false) KnowledgeType type,
            @RequestParam(required = false) String datasourceId,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) KnowledgeStatus status,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return guarded(() -> ingestion
                .items(type, blankToNull(datasourceId), blankToNull(agentId), status, safeLimit(limit))
                .stream()
                .map(this::itemView)
                .toList());
    }

    /**
     * Loads one item.
     *
     * @param id the item id
     * @return the item
     */
    @GetMapping("/items/{id}")
    public Mono<KnowledgeItemView> item(@PathVariable String id) {
        return guarded(() -> itemView(ingestion.requireItem(id)));
    }

    /**
     * Creates a text-only item.
     *
     * @param request the item
     * @return the persisted item
     */
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<KnowledgeItemView> create(@RequestBody KnowledgeItemRequest request) {
        return guarded(() -> itemView(ingestion.saveTextItem(request.toDraft())));
    }

    /**
     * Updates a text-only item (title/content only; the owner cannot change).
     *
     * @param id the item id
     * @param request the new title/content
     * @return the persisted item
     */
    @PutMapping("/items/{id}")
    public Mono<KnowledgeItemView> update(
            @PathVariable String id, @RequestBody KnowledgeItemRequest request) {
        return guarded(() -> itemView(ingestion.updateTextItem(id, request.toDraft())));
    }

    /**
     * Deletes an item with its chunks, vectors and objects.
     *
     * @param id the item id
     * @return an empty mono
     */
    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String id) {
        return guardedVoid(() -> ingestion.deleteItem(id));
    }

    /**
     * Retries a failed item from its recorded failure stage.
     *
     * @param id the item id
     * @return the item, now {@code PENDING}
     */
    @PostMapping("/items/{id}/retry")
    public Mono<KnowledgeItemView> retry(@PathVariable String id) {
        return guarded(() -> itemView(ingestion.retry(id)));
    }

    /**
     * Syncs a datasource's live schema into the metadata collection.
     *
     * @param datasourceId the datasource id
     * @return the sync summary
     */
    @PostMapping("/metadata/sync")
    public Mono<MetadataSyncView> syncMetadata(@RequestParam String datasourceId) {
        return guarded(() -> {
            MetadataSyncService.MetadataSyncResult result = metadataSync.sync(datasourceId);
            return new MetadataSyncView(
                    result.datasourceId(), result.tableCount(), result.itemIds());
        });
    }

    /**
     * Lists business-term mappings of a datasource.
     *
     * @param datasourceId the datasource id
     * @return the mappings
     */
    @GetMapping("/term-mappings")
    public Mono<List<TermMappingView>> termMappings(@RequestParam String datasourceId) {
        return guarded(() -> repository.listTermMappings(datasourceId).stream()
                .map(TermMappingView::of)
                .toList());
    }

    /**
     * Creates or replaces a business-term mapping.
     *
     * @param request the mapping
     * @return the stored mapping
     */
    @PostMapping("/term-mappings")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<TermMappingView> saveTermMapping(@RequestBody TermMappingRequest request) {
        return guarded(() -> TermMappingView.of(repository.upsertTermMapping(request.toMapping())));
    }

    /**
     * Deletes a business-term mapping.
     *
     * @param id the mapping id
     * @return an empty mono
     */
    @DeleteMapping("/term-mappings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteTermMapping(@PathVariable String id) {
        return guardedVoid(() -> repository.deleteTermMapping(id));
    }

    /**
     * Uploads a document and schedules parsing.
     *
     * @param file the uploaded file
     * @param knowledgeType {@code BUSINESS} or {@code AGENT}
     * @param datasourceId the owning datasource, or {@code null}
     * @param agentId the owning agent, or {@code null}
     * @param title the display title, or {@code null} to use the filename
     * @return the persisted document
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<KnowledgeDocumentView> upload(
            @RequestPart("file") FilePart file,
            @RequestPart("knowledgeType") String knowledgeType,
            @RequestPart(value = "datasourceId", required = false) String datasourceId,
            @RequestPart(value = "agentId", required = false) String agentId,
            @RequestPart(value = "title", required = false) String title) {
        KnowledgeType type = parseType(knowledgeType);
        String filename = file.filename();
        String mimeType = file.headers().getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : file.headers().getContentType().toString();
        String resolvedTitle = blankToNull(title) == null ? filename : title;
        long maxBytes = 512L * 1024L * 1024L;
        return readBytes(file, maxBytes)
                .flatMap(bytes -> guarded(() -> documentView(ingestion.upload(
                        new com.nekocong.text2sql.knowledge.service.KnowledgeItemDraft(
                                type, null, datasourceId, agentId, resolvedTitle, null, null, null),
                        filename,
                        mimeType,
                        bytes))));
    }

    /**
     * Imports a web page as a document.
     *
     * @param request the URL and owner
     * @return the persisted document
     */
    @PostMapping("/documents/url")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<KnowledgeDocumentView> importUrl(@RequestBody DocumentUrlRequest request) {
        return guarded(() -> documentView(ingestion.importUrl(request.toDraft(), request.url())));
    }

    /**
     * Lists documents.
     *
     * @param type optional knowledge-type filter
     * @param datasourceId optional datasource filter
     * @param agentId optional agent filter
     * @param status optional item-status filter
     * @param limit maximum rows
     * @return the documents, newest first
     */
    @GetMapping("/documents")
    public Mono<List<KnowledgeDocumentView>> documents(
            @RequestParam(required = false) KnowledgeType type,
            @RequestParam(required = false) String datasourceId,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) KnowledgeStatus status,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return guarded(() -> ingestion
                .documents(type, blankToNull(datasourceId), blankToNull(agentId), status, safeLimit(limit))
                .stream()
                .map(this::documentView)
                .toList());
    }

    /**
     * Re-parses a document with an explicit engine.
     *
     * @param id the document id
     * @param engine {@code tika}, {@code mineru}, or {@code null} for the configured engine
     * @return the document, now {@code PENDING}
     */
    @PostMapping("/documents/{id}/reparse")
    public Mono<KnowledgeDocumentView> reparse(
            @PathVariable String id, @RequestParam(required = false) String engine) {
        return guarded(() -> documentView(ingestion.reparse(id, blankToNull(engine))));
    }

    private KnowledgeItemView itemView(KnowledgeItem item) {
        KnowledgeItem enriched =
                repository.findDocumentByItem(item.id()).map(item::withDocument).orElse(item);
        return KnowledgeItemView.of(enriched);
    }

    private KnowledgeDocumentView documentView(KnowledgeDocument document) {
        return repository
                .findItem(document.itemId())
                .map(item -> KnowledgeDocumentView.of(document, item.status().name(), item.title()))
                .orElseGet(() -> KnowledgeDocumentView.of(document, null, null));
    }

    private static KnowledgeType parseType(String value) {
        try {
            return KnowledgeType.valueOf(value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "knowledgeType 必须是 METADATA/BUSINESS/AGENT");
        }
    }

    private static Mono<byte[]> readBytes(FilePart file, long maxBytes) {
        return DataBufferUtils.join(file.content(), (int) Math.min(maxBytes, Integer.MAX_VALUE))
                .map(
                        buffer -> {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            DataBufferUtils.release(buffer);
                            return bytes;
                        })
                .onErrorMap(
                        org.springframework.core.io.buffer.DataBufferLimitException.class,
                        ex -> new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件过大"));
    }

    private static int safeLimit(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 1000);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private <T> Mono<T> guarded(Supplier<T> action) {
        return Mono.fromCallable(action::get)
                .subscribeOn(blockingScheduler)
                .onErrorMap(
                        NoSuchElementException.class,
                        ex -> new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage()))
                .onErrorMap(
                        IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()))
                .onErrorMap(
                        ObjectStoreException.class,
                        ex -> new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()))
                .onErrorMap(
                        VectorStoreException.class,
                        ex -> new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    private Mono<Void> guardedVoid(Runnable action) {
        return Mono.fromRunnable(action)
                .subscribeOn(blockingScheduler)
                .then()
                .onErrorMap(
                        NoSuchElementException.class,
                        ex -> new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage()))
                .onErrorMap(
                        IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }
}
