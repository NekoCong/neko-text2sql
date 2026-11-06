package com.nekocong.text2sql.knowledge.service;

import com.nekocong.text2sql.knowledge.KnowledgeScope;
import com.nekocong.text2sql.knowledge.KnowledgeType;

/**
 * The user-supplied part of a knowledge item; scope, key and lifecycle are derived by the service.
 *
 * @param knowledgeType the collection
 * @param scope the requested scope, or {@code null} to derive it from the type
 * @param datasourceId the owning datasource, or {@code null}
 * @param agentId the owning agent, or {@code null}
 * @param title the display title
 * @param tableName the physical table (metadata items only), or {@code null}
 * @param content the text content (text-only items), or {@code null}
 * @param itemKey an explicit stable key, or {@code null} to derive one
 */
public record KnowledgeItemDraft(
        KnowledgeType knowledgeType,
        KnowledgeScope scope,
        String datasourceId,
        String agentId,
        String title,
        String tableName,
        String content,
        String itemKey) {
}
