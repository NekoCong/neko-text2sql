package com.nekocong.text2sql.nl2sql;

import com.nekocong.text2sql.gbi.ChartSpec;
import com.nekocong.text2sql.sql.model.QueryResult;

/**
 * The complete, non-streaming outcome of the NL2SQL pipeline.
 *
 * @param datasourceId the datasource the query targeted
 * @param dialect the SQL dialect
 * @param sql the validated SQL statement
 * @param explanation the model's short explanation, or {@code null}
 * @param result the query result
 * @param chart the inferred chart for the result (Generative BI)
 */
public record Text2SqlResult(
        String datasourceId,
        String dialect,
        String sql,
        String explanation,
        QueryResult result,
        ChartSpec chart) {}
