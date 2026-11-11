package com.nekocong.text2sql.access.api.dto;

import java.util.List;

/**
 * One role's datasource grant set, as datasource ids.
 *
 * <p>Returned by both the read and the replace endpoints, so a client can apply the result without a
 * second round trip. The list is ordered (by id) and free of duplicates.
 *
 * @param roleId the role id
 * @param datasourceIds the datasource ids the role may query
 */
public record RoleDatasourcesView(String roleId, List<String> datasourceIds) {

    /** Normalizes the id list so the view is structurally immutable. */
    public RoleDatasourcesView {
        datasourceIds = datasourceIds == null ? List.of() : List.copyOf(datasourceIds);
    }
}
