package com.nekocong.text2sql.access;

import java.util.List;

/**
 * A user as the admin API sees it: the account row plus its directly assigned roles.
 *
 * <p>The {@code app_user.role} legacy column and the {@code password_hash} are deliberately absent:
 * the legacy column is display/migration only and the hash must never leave the persistence layer.
 * Authorization comes solely from {@code access_user_role} (and inheritance, resolved by {@link
 * AccessContextResolver}).
 *
 * @param id the user id
 * @param username the unique username
 * @param displayName the display name, or {@code null}
 * @param enabled whether the account may sign in
 * @param createdAt the account creation time (epoch millis)
 * @param roles the directly assigned roles, ordered by code
 */
public record AccessUser(
        String id,
        String username,
        String displayName,
        boolean enabled,
        long createdAt,
        List<AccessRole> roles) {

    /** Normalizes the role list so the record is structurally immutable. */
    public AccessUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
