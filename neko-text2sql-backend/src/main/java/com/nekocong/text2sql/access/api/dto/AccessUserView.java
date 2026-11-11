package com.nekocong.text2sql.access.api.dto;

import com.nekocong.text2sql.access.AccessUser;
import java.util.List;

/**
 * A user as returned to admin clients. Deliberately free of any password material: the hash is never
 * returned, and the legacy {@code app_user.role} column is display/migration only, so it is not
 * exposed either — clients see the user's {@code access_user_role} assignments instead.
 *
 * @param id the user id
 * @param username the unique username
 * @param displayName the display name, or {@code null}
 * @param enabled whether the account may sign in
 * @param roles the directly assigned roles, ordered by code
 * @param createdAt the account creation time (epoch millis)
 */
public record AccessUserView(
        String id,
        String username,
        String displayName,
        boolean enabled,
        List<AccessUserRoleView> roles,
        long createdAt) {

    /** Normalizes the role list so the view is structurally immutable. */
    public AccessUserView {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /**
     * Maps a domain user to its view.
     *
     * @param user the user
     * @return the view
     */
    public static AccessUserView of(AccessUser user) {
        List<AccessUserRoleView> roles =
                user.roles().stream()
                        .map(role -> new AccessUserRoleView(role.id(), role.code(), role.name()))
                        .toList();
        return new AccessUserView(
                user.id(),
                user.username(),
                user.displayName(),
                user.enabled(),
                roles,
                user.createdAt());
    }
}
