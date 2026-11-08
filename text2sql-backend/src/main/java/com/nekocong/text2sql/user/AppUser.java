package com.nekocong.text2sql.user;

import com.nekocong.text2sql.security.Roles;

/**
 * An application user.
 *
 * @param id the user id (UUID)
 * @param username the unique username
 * @param passwordHash the BCrypt password hash
 * @param displayName the display name
 * @param role the role name ({@code admin}, {@code student} or {@code counselor})
 * @param enabled whether the account may sign in
 * @param tokenVersion the current token version; tokens issued at an older version are rejected
 */
public record AppUser(
        String id,
        String username,
        String passwordHash,
        String displayName,
        String role,
        boolean enabled,
        int tokenVersion) {

    /**
     * Reports whether this user has the admin role.
     *
     * @return {@code true} for admins
     */
    public boolean isAdmin() {
        return Roles.ADMIN.equalsIgnoreCase(role);
    }
}
