package com.nekocong.text2sql.access.api.dto;

import java.util.List;

/**
 * Request body for replacing a user's role assignments.
 *
 * <p>The list is the complete new set: a previously held role absent from it is revoked. Only an
 * explicit empty list clears every assignment; a missing body or a {@code null} {@code roleIds} field
 * is rejected, so a malformed client cannot wipe a user's access.
 *
 * @param roleIds the complete set of role ids to assign (de-duplicated)
 */
public record AccessUserRolesRequest(List<String> roleIds) {}
