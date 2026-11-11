package com.nekocong.text2sql.access.api.dto;

import com.nekocong.text2sql.access.DataScopeRule;

/**
 * A data-scope rule as returned to clients.
 *
 * @param id the rule id
 * @param roleId the owning role id
 * @param datasourceId the datasource id
 * @param tableName the table name
 * @param policy one of {@code ALL}, {@code FILTER}, {@code DENY}
 * @param columnName the filtered column, or {@code null} for {@code ALL}/{@code DENY}
 * @param principalAttribute the principal attribute code, or {@code null} for
 *     {@code ALL}/{@code DENY}
 */
public record DataScopeView(
        String id,
        String roleId,
        String datasourceId,
        String tableName,
        String policy,
        String columnName,
        String principalAttribute) {

    /**
     * Maps a domain rule to its view.
     *
     * @param rule the rule
     * @return the view
     */
    public static DataScopeView of(DataScopeRule rule) {
        return new DataScopeView(
                rule.id(),
                rule.roleId(),
                rule.datasourceId(),
                rule.tableName(),
                rule.policy() == null ? null : rule.policy().name(),
                rule.columnName(),
                rule.principalAttribute());
    }
}
