package com.nekocong.text2sql.knowledge;

import java.util.List;

/**
 * The explainable record of one retrieval.
 *
 * @param rewrittenQuery the query actually used for recall
 * @param businessHits number of business-knowledge hits
 * @param agentHits number of agent-knowledge hits
 * @param candidateTables tables recalled before foreign-key expansion
 * @param finalTables tables selected for the prompt
 * @param fallback whether the retrieval fell back to the full schema
 * @param fallbackReason why it fell back, or {@code null}
 * @param citations citations produced by this retrieval
 */
public record SearchTrace(
        String rewrittenQuery,
        int businessHits,
        int agentHits,
        List<String> candidateTables,
        List<String> finalTables,
        boolean fallback,
        String fallbackReason,
        List<Citation> citations) {
}
