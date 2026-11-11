package com.nekocong.text2sql.controller.dto;

/**
 * Login request.
 *
 * @param username the username
 * @param password the raw password
 */
public record LoginRequest(String username, String password) {}
