package com.nekocong.text2sql.access;

/**
 * One {@code access_role_permission} row resolved to a permission key, as read for the in-memory
 * last-administrator lockout evaluation.
 *
 * @param roleId the role id
 * @param permissionKey the granted permission key
 */
public record RolePermissionLink(String roleId, String permissionKey) {
}
