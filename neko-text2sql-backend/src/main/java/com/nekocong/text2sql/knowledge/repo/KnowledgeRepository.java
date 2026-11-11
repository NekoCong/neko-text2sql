package com.nekocong.text2sql.knowledge.repo;

import com.nekocong.text2sql.knowledge.KnowledgeDocument;
import com.nekocong.text2sql.knowledge.KnowledgeItem;
import com.nekocong.text2sql.knowledge.KnowledgeStatus;
import com.nekocong.text2sql.knowledge.KnowledgeType;
import com.nekocong.text2sql.knowledge.ParseStatus;
import com.nekocong.text2sql.knowledge.TermMapping;
import java.util.List;
import java.util.Optional;

/**
 * JDBC persistence for knowledge items, documents and term mappings.
 *
 * <p>Chunk + vector persistence lives in {@link com.nekocong.text2sql.knowledge.store.VectorStore};
 * this repository owns everything that is not a vector.
 *
 * <p>All methods are blocking and must run on a bounded-elastic scheduler.
 */
public interface KnowledgeRepository {

    /**
     * Upserts an item by {@code item_key}, returning the stored row.
     *
     * @param item the item
     * @return the stored item (with its generated id/timestamps)
     */
    KnowledgeItem upsertItem(KnowledgeItem item);

    /**
     * Finds an item by id.
     *
     * @param id the item id
     * @return the item, or empty
     */
    Optional<KnowledgeItem> findItem(String id);

    /**
     * Finds an item by its stable key.
     *
     * @param itemKey the key
     * @return the item, or empty
     */
    Optional<KnowledgeItem> findItemByKey(String itemKey);

    /**
     * Lists items, newest first.
     *
     * @param type optional type filter
     * @param datasourceId optional datasource filter
     * @param agentId optional agent filter
     * @param status optional status filter
     * @param limit maximum rows
     * @return the items
     */
    List<KnowledgeItem> listItems(
            KnowledgeType type, String datasourceId, String agentId, KnowledgeStatus status, int limit);

    /**
     * Updates the lifecycle columns of an item.
     *
     * @param id the item id
     * @param status the new status
     * @param stage the failed stage, or {@code null}
     * @param reason the failure reason, or {@code null}
     * @param embeddingModel the embedding model, or {@code null}
     */
    void updateItemStatus(
            String id, KnowledgeStatus status, String failureStage, String reason, String embeddingModel);

    /**
     * Deletes an item row. Chunks and objects are deleted by the caller.
     *
     * @param id the item id
     */
    void deleteItem(String id);

    /**
     * Upserts a document by item id.
     *
     * @param document the document
     * @return the stored document
     */
    KnowledgeDocument upsertDocument(KnowledgeDocument document);

    /**
     * Finds a document by id.
     *
     * @param id the document id
     * @return the document, or empty
     */
    Optional<KnowledgeDocument> findDocument(String id);

    /**
     * Finds the document of an item.
     *
     * @param itemId the item id
     * @return the document, or empty
     */
    Optional<KnowledgeDocument> findDocumentByItem(String itemId);

    /**
     * Finds a document by its content hash (deduplication).
     *
     * @param sha256 the content hash
     * @return the document, or empty
     */
    Optional<KnowledgeDocument> findDocumentBySha(String sha256);

    /**
     * Lists documents, newest first.
     *
     * @param type optional knowledge-type filter
     * @param datasourceId optional datasource filter
     * @param agentId optional agent filter
     * @param status optional item-status filter
     * @param limit maximum rows
     * @return the documents
     */
    List<KnowledgeDocument> listDocuments(
            KnowledgeType type, String datasourceId, String agentId, KnowledgeStatus status, int limit);

    /**
     * Updates the parse columns of a document.
     *
     * @param id the document id
     * @param status the parse state
     * @param engine the engine id, or {@code null}
     * @param version the engine version, or {@code null}
     * @param pageCount the page count, or {@code null}
     * @param reason the failure reason, or {@code null}
     */
    void updateDocumentParse(
            String id, ParseStatus status, String engine, String version, Integer pageCount, String reason);

    /**
     * Deletes a document row.
     *
     * @param id the document id
     */
    void deleteDocument(String id);

    /**
     * Lists term mappings of a datasource.
     *
     * @param datasourceId the datasource
     * @return the mappings
     */
    List<TermMapping> listTermMappings(String datasourceId);

    /**
     * Upserts a term mapping by {@code (datasource_id, term)}.
     *
     * @param mapping the mapping
     * @return the stored mapping
     */
    TermMapping upsertTermMapping(TermMapping mapping);

    /**
     * Deletes a term mapping.
     *
     * @param id the mapping id
     */
    void deleteTermMapping(String id);

    /**
     * Finds documents that are still {@code PENDING}/{@code PARSING}/{@code EMBEDDING} after a
     * timeout, so a compensation scan can retry them.
     *
     * @param olderThan the cutoff instant
     * @param limit maximum rows
     * @return the stale items
     */
    List<KnowledgeItem> findStaleItems(java.time.Instant olderThan, int limit);
}
