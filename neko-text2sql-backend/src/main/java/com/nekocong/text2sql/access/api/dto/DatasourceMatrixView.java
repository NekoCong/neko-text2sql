package com.nekocong.text2sql.access.api.dto;

import java.util.List;

/**
 * The role → datasource matrix shown on the admin page, keyed by role records (not role-code
 * constants) so custom roles can be granted datasources.
 *
 * @param roles every role (built-in and custom), ordered by code, each with its datasource grants
 * @param datasources every registered datasource, in registration order
 */
public record DatasourceMatrixView(
        List<DatasourceMatrixRoleView> roles, List<DatasourceMatrixDatasourceView> datasources) {

    /** Normalizes the collections so the view is structurally immutable. */
    public DatasourceMatrixView {
        roles = roles == null ? List.of() : List.copyOf(roles);
        datasources = datasources == null ? List.of() : List.copyOf(datasources);
    }
}
