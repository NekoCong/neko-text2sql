package com.nekocong.text2sql.controller.dto;

/**
 * Authentication response.
 *
 * @param token the JWT access token
 * @param expiresIn the token lifetime, in seconds
 * @param user the authenticated user
 */
public record AuthResponse(String token, long expiresIn, UserView user) {}
