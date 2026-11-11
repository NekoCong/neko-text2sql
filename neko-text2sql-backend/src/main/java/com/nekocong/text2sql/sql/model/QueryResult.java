package com.nekocong.text2sql.sql.model;

import java.util.List;
import java.util.Map;

/**
 * The outcome of executing a read-only query.
 *
 * @param columns the result columns, in order
 * @param rows the rows, each keyed by column name (insertion-ordered)
 * @param rowCount the number of returned rows
 * @param truncated whether more rows existed beyond the configured row limit
 * @param elapsedMs execution time in milliseconds
 */
public record QueryResult(
        List<ColumnMeta> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        boolean truncated,
        long elapsedMs) {}
