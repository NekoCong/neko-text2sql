package com.nekocong.text2sql.nl2sql;

/**
 * The generated (and validated) SQL for a question, plus the model's explanation.
 *
 * @param datasourceId the datasource the query targets
 * @param dialect the SQL dialect
 * @param sql the validated SQL statement
 * @param explanation the model's short explanation, or {@code null}
 */
public record SqlEvent(String datasourceId, String dialect, String sql, String explanation)
        implements Text2SqlEvent {}
