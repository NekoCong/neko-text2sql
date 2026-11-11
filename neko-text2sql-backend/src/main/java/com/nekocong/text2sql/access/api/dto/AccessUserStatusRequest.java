package com.nekocong.text2sql.access.api.dto;

/**
 * Request body for enabling or disabling a user.
 *
 * @param enabled whether the account may sign in (required)
 */
public record AccessUserStatusRequest(Boolean enabled) {}
