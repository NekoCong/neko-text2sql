package com.nekocong.text2sql.knowledge;

/**
 * A business term mapped to a physical table or column.
 *
 * @param id the mapping id
 * @param datasourceId the owning datasource
 * @param term the colloquial term
 * @param targetType {@code TABLE} or {@code COLUMN}
 * @param tableName the target table
 * @param columnName the target column (for column mappings)
 * @param description a short explanation
 */
public record TermMapping(
        String id,
        String datasourceId,
        String term,
        String targetType,
        String tableName,
        String columnName,
        String description) {
}
