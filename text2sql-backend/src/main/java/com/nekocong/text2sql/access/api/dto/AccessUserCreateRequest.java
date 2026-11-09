package com.nekocong.text2sql.access.api.dto;

import java.util.List;

/**
 * Request body for creating a user.
 *
 * <p>The {@code roleIds} are role ids (not codes) and are the only way to assign access: the legacy
 * {@code app_user.role} column is never settable by a client, so a new user always keeps its column
 * default {@code 'student'} and derives its real authorization from these assignments.
 *
 * @param username the unique username (required)
 * @param password the raw password (required; never logged, audited or returned)
 * @param displayName the optional display name (blank becomes {@code null})
 * @param roleIds the role ids to assign (at least one; de-duplicated)
 */
public record AccessUserCreateRequest(
        String username, String password, String displayName, List<String> roleIds) {}
