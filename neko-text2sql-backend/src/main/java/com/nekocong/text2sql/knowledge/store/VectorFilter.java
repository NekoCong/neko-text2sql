package com.nekocong.text2sql.knowledge.store;

import com.nekocong.text2sql.knowledge.KnowledgeType;
import java.util.Set;

/**
 * The mandatory isolation filter of a vector query.
 *
 * <p>Applied inside the SQL (not after it), so out-of-scope rows never reach the application.
 *
 * @param knowledgeType the collection to search
 * @param datasourceId the datasource to scope to, or {@code null} for global-only
 * @param agentId the agent whose private knowledge may be searched, or {@code null}
 * @param allowedTables the caller's table whitelist, or {@code null} for unrestricted
 * @param allowedItemIds an explicit item allow-list, or {@code null} for no restriction
 */
public record VectorFilter(
        KnowledgeType knowledgeType,
        String datasourceId,
        String agentId,
        Set<String> allowedTables,
        Set<String> allowedItemIds) {

    /** Normalizes the collections so the filter is structurally immutable. */
    public VectorFilter {
        allowedTables = allowedTables == null ? null : Set.copyOf(allowedTables);
        allowedItemIds = allowedItemIds == null ? null : Set.copyOf(allowedItemIds);
    }
}
