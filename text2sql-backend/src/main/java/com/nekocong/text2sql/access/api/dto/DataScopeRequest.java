package com.nekocong.text2sql.access.api.dto;

/**
 * Request body for upserting one data-scope rule.
 *
 * <p>The rule is identified by ({@code roleId}, {@code datasourceId}, {@code tableName}): sending it
 * again updates that table's policy instead of creating a second row. There is deliberately no
 * predicate field — a policy can only reference a validated column and one entry of the closed
 * {@code PrincipalAttribute} catalog, never raw SQL.
 *
 * <p>{@code FILTER} requires both {@code columnName} and {@code principalAttribute}; {@code ALL} and
 * {@code DENY} must carry neither. The table and column must be bare identifiers present in the
 * datasource's live metadata (the stored values are the canonical, introspected spellings).
 *
 * <p>Type compatibility: {@code studentId} is rendered as a bare number, so it must target a numeric
 * column; {@code classIds} is rendered as an {@code IN} list; {@code userId}, {@code username} and
 * {@code studentNo} are compared as text. A single-valued numeric attribute on a known text column is
 * rejected as a {@code 422}.
 *
 * @param roleId the owning role id (required)
 * @param datasourceId the datasource id (required; must exist in the registry)
 * @param tableName the table name (required; must exist in the datasource's live metadata)
 * @param policy one of {@code ALL}, {@code FILTER}, {@code DENY} (required)
 * @param columnName the filtered column, required for {@code FILTER} and forbidden otherwise
 * @param principalAttribute the principal attribute code, required for {@code FILTER} and forbidden
 *     otherwise
 */
public record DataScopeRequest(
        String roleId,
        String datasourceId,
        String tableName,
        String policy,
        String columnName,
        String principalAttribute) {}
