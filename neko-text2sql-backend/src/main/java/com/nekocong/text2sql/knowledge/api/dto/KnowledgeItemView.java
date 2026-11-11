package com.nekocong.text2sql.knowledge.api.dto;

import com.nekocong.text2sql.knowledge.FailureStage;
import com.nekocong.text2sql.knowledge.KnowledgeItem;
import com.nekocong.text2sql.knowledge.KnowledgeScope;
import com.nekocong.text2sql.knowledge.KnowledgeStatus;
import com.nekocong.text2sql.knowledge.KnowledgeType;
import java.time.Instant;

/**
 * A knowledge item as returned to the admin UI.
 *
 * @param id the item id
 * @param knowledgeType the collection
 * @param scope the isolation dimension
 * @param datasourceId the owning datasource, or {@code null}
 * @param agentId the owning agent, or {@code null}
 * @param itemKey the stable key
 * @param title the display title
 * @param tableName the physical table (metadata items), or {@code null}
 * @param content the indexable text, or {@code null}
 * @param status the lifecycle state
 * @param failureStage the failed stage, or {@code null}
 * @param failureReason a readable failure reason, or {@code null}
 * @param embeddingModel the embedding model, or {@code null}
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 * @param document the attached document, or {@code null}
 */
public record KnowledgeItemView(
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
        KnowledgeDocumentView document) {

    /**
     * Maps an item.
     *
     * @param item the item
     * @return the view
     */
    public static KnowledgeItemView of(KnowledgeItem item) {
        KnowledgeDocumentView document = item.document() == null
                ? null
                : KnowledgeDocumentView.of(item.document(), item.status().name(), item.title());
        return new KnowledgeItemView(
                item.id(),
                item.knowledgeType(),
                item.scope(),
                item.datasourceId(),
                item.agentId(),
                item.itemKey(),
                item.title(),
                item.tableName(),
                item.content(),
                item.status(),
                item.failureStage(),
                item.failureReason(),
                item.embeddingModel(),
                item.createdAt(),
                item.updatedAt(),
                document);
    }
}
