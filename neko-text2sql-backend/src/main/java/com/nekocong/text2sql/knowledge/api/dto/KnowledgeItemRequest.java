package com.nekocong.text2sql.knowledge.api.dto;

import com.nekocong.text2sql.knowledge.KnowledgeScope;
import com.nekocong.text2sql.knowledge.KnowledgeType;
import com.nekocong.text2sql.knowledge.service.KnowledgeItemDraft;

/**
 * Request body for creating or updating a text-only knowledge item.
 *
 * @param knowledgeType the collection (ignored on update: the type cannot change)
 * @param scope the requested scope, or {@code null} to derive it from the type
 * @param datasourceId the owning datasource, or {@code null}
 * @param agentId the owning agent, or {@code null}
 * @param title the display title
 * @param tableName the physical table (metadata items only), or {@code null}
 * @param content the text content
 * @param itemKey an explicit stable key, or {@code null} to derive one
 */
public record KnowledgeItemRequest(
        KnowledgeType knowledgeType,
        KnowledgeScope scope,
        String datasourceId,
        String agentId,
        String title,
        String tableName,
        String content,
        String itemKey) {

    /**
     * Converts the request into an ingestion draft.
     *
     * @return the draft
     */
    public KnowledgeItemDraft toDraft() {
        return new KnowledgeItemDraft(
                knowledgeType, scope, datasourceId, agentId, title, tableName, content, itemKey);
    }
}
