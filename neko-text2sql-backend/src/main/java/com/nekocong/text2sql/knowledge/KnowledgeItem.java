package com.nekocong.text2sql.knowledge;

import java.time.Instant;

/**
 * One knowledge item: a table summary, a business rule or an agent document.
 *
 * @param id the item id
 * @param knowledgeType which of the three collections it belongs to
 * @param scope the isolation dimension
 * @param datasourceId the owning datasource (when scoped to one)
 * @param agentId the owning agent (when scoped to one)
 * @param itemKey a stable key used for idempotent upserts
 * @param title the display title
 * @param tableName the physical table a metadata item describes, otherwise {@code null}
 * @param content the indexable text
 * @param status the lifecycle state
 * @param failureStage the failed stage, or {@code null}
 * @param failureReason a readable failure reason, or {@code null}
 * @param embeddingModel the model that produced the vectors, or {@code null}
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 * @param document the attached document, or {@code null} for text-only items
 */
public record KnowledgeItem(
        String id,
        KnowledgeType knowledgeType,
        KnowledgeScope scope,
        String datasourceId,
        String agentId,
        String itemKey,
        String title,
        String tableName,
        String content,
        KnowledgeStatus status,
        FailureStage failureStage,
        String failureReason,
        String embeddingModel,
        Instant createdAt,
        Instant updatedAt,
        KnowledgeDocument document) {

    /** @return a copy carrying the given document */
    public KnowledgeItem withDocument(KnowledgeDocument value) {
        return new KnowledgeItem(
                id,
                knowledgeType,
                scope,
                datasourceId,
                agentId,
                itemKey,
                title,
                tableName,
                content,
                status,
                failureStage,
                failureReason,
                embeddingModel,
                createdAt,
                updatedAt,
                value);
    }

    /** @return a copy with the given lifecycle state */
    public KnowledgeItem withStatus(KnowledgeStatus value, FailureStage stage, String reason) {
        return new KnowledgeItem(
                id,
                knowledgeType,
                scope,
                datasourceId,
                agentId,
                itemKey,
                title,
                tableName,
                content,
                value,
                stage,
                reason,
                embeddingModel,
                createdAt,
                updatedAt,
                document);
    }

    /** @return a copy with the embedding model recorded */
    public KnowledgeItem withEmbeddingModel(String value) {
        return new KnowledgeItem(
                id,
                knowledgeType,
                scope,
                datasourceId,
                agentId,
                itemKey,
                title,
                tableName,
                content,
                status,
                failureStage,
                failureReason,
                value,
                createdAt,
                updatedAt,
                document);
    }
}
