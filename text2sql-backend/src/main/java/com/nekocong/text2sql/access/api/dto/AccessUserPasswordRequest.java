package com.nekocong.text2sql.access.api.dto;

/**
 * Request body for resetting a user's password.
 *
 * @param password the new raw password (required; never logged, audited or returned)
 */
public record AccessUserPasswordRequest(String password) {}
