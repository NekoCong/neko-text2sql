package com.nekocong.text2sql.knowledge.api.dto;

import com.nekocong.text2sql.knowledge.TermMapping;

/**
 * A business-term mapping as returned to the admin UI.
 *
 * @param id the mapping id
 * @param datasourceId the owning datasource
 * @param term the colloquial term
 * @param targetType {@code TABLE} or {@code COLUMN}
 * @param tableName the target table
 * @param columnName the target column, or {@code null}
 * @param description a short explanation, or {@code null}
 */
public record TermMappingView(
        String id,
        String datasourceId,
        String term,
        String targetType,
        String tableName,
        String columnName,
        String description) {

    /**
     * Maps a term mapping.
     *
     * @param mapping the mapping
     * @return the view
     */
    public static TermMappingView of(TermMapping mapping) {
        return new TermMappingView(
                mapping.id(),
                mapping.datasourceId(),
                mapping.term(),
                mapping.targetType(),
                mapping.tableName(),
                mapping.columnName(),
                mapping.description());
    }
}
