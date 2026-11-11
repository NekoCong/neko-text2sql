package com.nekocong.text2sql.access.api.dto;

import java.util.List;

/**
 * One role row in the datasource matrix.
 *
 * @param id the role id
 * @param code the stable code
 * @param name the display name
 * @param builtIn whether the role is seeded and protected
 * @param enabled whether the role participates in effective access
 * @param datasourceIds the datasources granted to the role, sorted and free of duplicates
 */
public record DatasourceMatrixRoleView(
        String id,
        String code,
        String name,
        boolean builtIn,
        boolean enabled,
        List<String> datasourceIds) {

    /** Normalizes the grant list so the view is structurally immutable. */
    public DatasourceMatrixRoleView {
        datasourceIds = datasourceIds == null ? List.of() : List.copyOf(datasourceIds);
    }
}
