package com.nekocong.text2sql.knowledge.store;

import com.nekocong.text2sql.knowledge.KnowledgeChunk;
import com.nekocong.text2sql.knowledge.KnowledgeItem;
import java.util.List;
import java.util.Optional;

/**
 * Chunk + vector persistence and retrieval.
 *
 * <p>Owns the {@code knowledge_chunk} table: chunk writes, cascading deletes, similarity search and
 * the deterministic lexical fallback used when no embedding model is configured.
 */
public interface VectorStore {

    /**
     * Replaces every chunk of an item atomically.
     *
     * @param item the owning item (carries the isolation attributes used by the in-memory store)
     * @param chunks the new chunks (empty clears the item)
     */
    void replaceChunks(KnowledgeItem item, List<KnowledgeChunk> chunks);

    /**
     * Deletes every chunk of an item.
     *
     * @param itemId the owning item
     */
    void deleteByItem(String itemId);

    /**
     * Lists an item's chunks in chunk-index order.
     *
     * @param itemId the owning item
     * @return the chunks
     */
    List<KnowledgeChunk> listByItem(String itemId);

    /**
     * Finds one chunk.
     *
     * @param chunkId the chunk id
     * @return the chunk, or empty
     */
    Optional<KnowledgeChunk> findChunk(String chunkId);

    /**
     * Cosine-similarity search inside the filter.
     *
     * @param queryVector the query embedding (1024 dimensions)
     * @param filter the mandatory isolation filter
     * @param topK the maximum number of hits
     * @return hits ordered by descending similarity
     * @throws VectorStoreException when the vector database is unavailable
     */
    List<VectorHit> search(double[] queryVector, VectorFilter filter, int topK);

    /**
     * Deterministic lexical search used when embeddings are unavailable.
     *
     * @param query the raw query text
     * @param filter the mandatory isolation filter
     * @param topK the maximum number of hits
     * @return hits ordered by descending lexical score
     * @throws VectorStoreException when the vector database is unavailable
     */
    List<VectorHit> searchLexical(String query, VectorFilter filter, int topK);
}
