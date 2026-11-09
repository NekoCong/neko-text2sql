package com.nekocong.text2sql.controller.dto;

import java.util.List;

/**
 * The authenticated user, as returned to the client.
 *
 * <p>{@code role} is the legacy display-only claim from the JWT; {@code roles} and {@code permissions}
 * are the caller's <em>effective</em> access resolved from the access-control tables on every call, so
 * navigation and route guards can gate on them without trusting the token.
 *
 * @param id the user id
 * @param username the username
 * @param displayName the display name (may be {@code null})
 * @param role the role name ({@code admin}, {@code student} or {@code counselor})
 * @param roles the effective role codes (enabled direct roles + enabled ancestors), sorted
 * @param permissions the effective permission keys, sorted
 */
public record UserView(
        String id,
        String username,
        String displayName,
        String role,
        List<String> roles,
        List<String> permissions) {

    /** Normalizes the collections so the view is structurally immutable. */
    public UserView {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
