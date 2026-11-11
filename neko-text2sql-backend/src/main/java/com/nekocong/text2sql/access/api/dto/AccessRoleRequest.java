package com.nekocong.text2sql.access.api.dto;

/**
 * Request body for creating or updating a role.
 *
 * <p>On create, {@code code} is required and {@code parentId} may be omitted or {@code null} for a
 * root role. On update, {@code code} may be omitted (or sent identical) but never changed, and every
 * other field follows "omit/{@code null} = keep current value" — with one explicit exception:
 * {@code parentId} set to the empty string {@code ""} clears the parent. A new role defaults to
 * {@code enabled=true}.
 *
 * @param code the stable role code (required on create; immutable)
 * @param name the display name (required on create)
 * @param description the description (at most 512 characters)
 * @param parentId the parent role id; on update {@code ""} clears it
 * @param enabled whether the role participates in effective access (defaults to {@code true} on
 *     create)
 */
public record AccessRoleRequest(
        String code, String name, String description, String parentId, Boolean enabled) {}
