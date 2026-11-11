package com.nekocong.text2sql.controller.dto;

/**
 * Registration request.
 *
 * @param username the desired username
 * @param password the raw password
 * @param displayName the optional display name
 */
public record RegisterRequest(String username, String password, String displayName) {}
