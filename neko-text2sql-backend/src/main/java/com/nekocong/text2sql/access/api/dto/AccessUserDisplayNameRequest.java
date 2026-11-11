package com.nekocong.text2sql.access.api.dto;

/**
 * Request body for editing a user's display name.
 *
 * <p>The display name is the only mutable free-form field on a user; a {@code null} or blank value
 * clears it.
 *
 * @param displayName the new display name (at most 100 characters), or {@code null}/blank to clear
 */
public record AccessUserDisplayNameRequest(String displayName) {}
