package com.nekocong.text2sql.knowledge.api.dto;

/**
 * Request body for a knowledge retrieval (search and debug share the same shape).
 *
 * @param query the natural-language question
 * @param datasourceId the datasource to scope to, or {@code null} for the default
 * @param agentId the agent whose private knowledge may be used, or {@code null}
 * @param topK maximum hits per route, or {@code null} for the configured value
 */
public record KnowledgeQueryRequest(
        String query, String datasourceId, String agentId, Integer topK) {
}
