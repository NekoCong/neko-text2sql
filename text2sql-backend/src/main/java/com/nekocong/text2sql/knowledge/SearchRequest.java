package com.nekocong.text2sql.knowledge;

import java.util.List;

/**
 * A retrieval request.
 *
 * @param query the natural-language question
 * @param datasourceId the datasource to scope metadata to
 * @param agentId the agent whose private knowledge may be used
 * @param topK maximum hits per route
 */
public record SearchRequest(String query, String datasourceId, String agentId, Integer topK) {
}
