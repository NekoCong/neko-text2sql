package com.nekocong.text2sql.access.api.dto;

import com.nekocong.text2sql.access.AccessPermission;

/**
 * A permission catalog entry as returned to clients.
 *
 * @param id the permission id
 * @param key the permission key ({@code chat.use})
 * @param module the module ({@code chat})
 * @param action the action within the module ({@code use})
 * @param description the human-readable description, or {@code null}
 * @param builtIn whether the catalog row is seeded
 */
public record AccessPermissionView(
        String id,
        String key,
        String module,
        String action,
        String description,
        boolean builtIn) {

    /**
     * Maps a catalog entry to its view.
     *
     * @param permission the permission
     * @return the view
     */
    public static AccessPermissionView of(AccessPermission permission) {
        return new AccessPermissionView(
                permission.id(),
                permission.key(),
                permission.module(),
                permission.action(),
                permission.description(),
                permission.builtIn());
    }
}
