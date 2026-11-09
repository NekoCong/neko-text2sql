package com.nekocong.text2sql.access.api.dto;

import com.nekocong.text2sql.access.AccessRole;

/**
 * A role as returned to clients.
 *
 * @param id the role id
 * @param code the stable code
 * @param name the display name
 * @param description the description, or {@code null}
 * @param parentId the parent role, or {@code null}
 * @param builtIn whether the role is seeded and protected
 * @param enabled whether the role participates in effective access
 */
public record AccessRoleView(
        String id,
        String code,
        String name,
        String description,
        String parentId,
        boolean builtIn,
        boolean enabled) {

    /**
     * Maps a domain role to its view.
     *
     * @param role the role
     * @return the view
     */
    public static AccessRoleView of(AccessRole role) {
        return new AccessRoleView(
                role.id(),
                role.code(),
                role.name(),
                role.description(),
                role.parentId(),
                role.builtIn(),
                role.enabled());
    }
}
