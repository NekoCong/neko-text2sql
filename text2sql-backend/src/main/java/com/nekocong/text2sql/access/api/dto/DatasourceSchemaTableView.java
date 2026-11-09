package com.nekocong.text2sql.access.api.dto;

import java.util.List;

/**
 * One table of a datasource's live metadata, as returned to the data-scope editor.
 *
 * @param name the table name
 * @param columns the table's columns, in declaration order
 */
public record DatasourceSchemaTableView(String name, List<DatasourceSchemaColumnView> columns) {

    /** Normalizes the column list so the view is structurally immutable. */
    public DatasourceSchemaTableView {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
