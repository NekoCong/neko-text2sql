package com.nekocong.text2sql.knowledge.service;

import com.nekocong.text2sql.knowledge.FailureStage;

/**
 * Published inside the ingestion transaction; the async worker starts only after commit.
 *
 * @param itemId the item to process
 * @param resumeFrom the stage to resume from ({@code null} = full pipeline)
 * @param engineOverride an explicit parser engine for a reparse, or {@code null}
 */
public record KnowledgeItemSavedEvent(String itemId, FailureStage resumeFrom, String engineOverride) {

    /** @return a full-pipeline event */
    public static KnowledgeItemSavedEvent full(String itemId) {
        return new KnowledgeItemSavedEvent(itemId, null, null);
    }
}
