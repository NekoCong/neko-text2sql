package com.nekocong.text2sql.knowledge.api.dto;

import com.nekocong.text2sql.knowledge.KnowledgeType;
import com.nekocong.text2sql.knowledge.service.KnowledgeItemDraft;

/**
 * Request body for importing a web page as knowledge.
 *
 * @param url the page URL
 * @param knowledgeType {@code BUSINESS} or {@code AGENT}
 * @param datasourceId the owning datasource, or {@code null}
 * @param agentId the owning agent, or {@code null}
 * @param title the display title, or {@code null} to derive one from the URL
 */
public record DocumentUrlRequest(
        String url, KnowledgeType knowledgeType, String datasourceId, String agentId, String title) {

    /**
     * Converts the request into an ingestion draft.
     *
     * @return the draft
     */
    public KnowledgeItemDraft toDraft() {
        return new KnowledgeItemDraft(
                knowledgeType, null, datasourceId, agentId, title, null, null, null);
    }
}
