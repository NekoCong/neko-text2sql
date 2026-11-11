package com.nekocong.text2sql.access;

/**
 * One {@code access_role_datasource} row, as read for the matrix: a role id and a granted datasource
 * id. The enforcement source is the row itself, so the matrix is keyed by role records rather than
 * role-code constants.
 *
 * @param roleId the role id
 * @param datasourceId the datasource id
 */
public record RoleDatasource(String roleId, String datasourceId) {
}
