package com.nekocong.text2sql.access.api.dto;

import java.util.List;

/**
 * A datasource's live table/column metadata, returned to the data-scope editor.
 *
 * <p>Read-only: the table and column selectors of the data-scope dialog are populated from this, so a
 * client can offer only names that actually exist in the datasource (the real boundary remains the
 * {@code DataScopeService} validation on upsert).
 *
 * @param datasourceId the datasource id
 * @param tables the user tables, sorted by name
 */
public record DatasourceSchemaView(String datasourceId, List<DatasourceSchemaTableView> tables) {

    /** Normalizes the table list so the view is structurally immutable. */
    public DatasourceSchemaView {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }
}
