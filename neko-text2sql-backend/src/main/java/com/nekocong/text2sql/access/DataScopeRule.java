package com.nekocong.text2sql.access;

/**
 * One per-role, per-datasource, per-table data policy.
 *
 * @param id the policy id
 * @param roleId the owning role
 * @param datasourceId the datasource
 * @param tableName the table
 * @param policy the policy type
 * @param columnName the filtered column (required for {@code FILTER}), or {@code null}
 * @param principalAttribute the principal attribute code (required for {@code FILTER}), or {@code null}
 */
public record DataScopeRule(
        String id,
        String roleId,
        String datasourceId,
        String tableName,
        DataScopePolicy policy,
        String columnName,
        String principalAttribute) {
}
