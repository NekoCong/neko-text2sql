package com.nekocong.text2sql.access.api.dto;

/**
 * Request body for creating or updating a role.
 *
 * <p>On create, {@code code} is required. On update it may be omitted (or sent identical) but never
 * changed: the code is immutable once assigned. Fields left {@code null} on update keep their
 * current value, except {@code parentId}: {@code null} clears the parent.
 *
 * @param code the stable role code (required on create; immutable)
 * @param name the display name
 * @param description the description
 * @param parentId the parent role id (optional)
 * @param enabled whether the role participates in effective access (defaults to {@code true} on
 *     create)
 */
public record AccessRoleRequest(
        String code, String name, String description, String parentId, Boolean enabled) {}
