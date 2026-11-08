package com.nekocong.text2sql.access;

/**
 * A role record. {@code code} is immutable and used only for display/diagnostics; enforcement
 * never branches on it.
 *
 * @param id the role id
 * @param code the stable code
 * @param name the display name
 * @param description the description, or {@code null}
 * @param parentId the parent role, or {@code null}
 * @param builtIn whether the role is seeded and protected
 * @param enabled whether the role participates in effective access
 */
public record AccessRole(
        String id,
        String code,
        String name,
        String description,
        String parentId,
        boolean builtIn,
        boolean enabled) {
}
