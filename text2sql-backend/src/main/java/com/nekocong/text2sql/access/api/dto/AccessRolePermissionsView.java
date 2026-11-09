package com.nekocong.text2sql.access.api.dto;

import java.util.List;

/**
 * One role's permission set, as permission keys.
 *
 * <p>Returned by both the read and the replace endpoints, so a client can apply the result without
 * a second round trip. The list is ordered (by key) and free of duplicates.
 *
 * @param roleId the role id
 * @param permissions the permission keys the role holds
 */
public record AccessRolePermissionsView(String roleId, List<String> permissions) {

    /** Normalizes the key list so the view is structurally immutable. */
    public AccessRolePermissionsView {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
