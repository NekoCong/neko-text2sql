package com.nekocong.text2sql.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Role names and helpers shared by token issuance and authorization checks.
 *
 * <p>Three roles are supported: {@link #ADMIN} sees everything, {@link #STUDENT} is limited to its
 * own records, and {@link #COUNSELOR} is limited to the classes it manages. Row-level scoping is
 * enforced by the data-permission layer; this class only carries the role vocabulary.
 */
public final class Roles {

    /** JWT claim carrying the role. */
    public static final String CLAIM = "role";

    /** Full-access role: no row filtering, and the only role that may maintain datasource access. */
    public static final String ADMIN = "admin";

    /** Student: only the caller's own rows and class. */
    public static final String STUDENT = "student";

    /** Counselor: every class the caller manages. */
    public static final String COUNSELOR = "counselor";

    /**
     * Legacy non-admin role from the e-commerce demo dataset.
     *
     * @deprecated no account is seeded with this role any more; it is only recognized so stale rows
     *     fail closed instead of being mistaken for a scoped role.
     */
    @Deprecated
    public static final String USER = "user";

    private Roles() {}

    /**
     * Canonicalizes a role name.
     *
     * <p>Returns {@code null} for anything unrecognized (including the legacy {@link #USER}). Callers
     * must treat {@code null} as "no data permission": silently downgrading an unknown role to a
     * scoped role would grant a scope the account was never configured for.
     *
     * @param role the raw role value (may be {@code null})
     * @return one of {@link #ADMIN}, {@link #STUDENT}, {@link #COUNSELOR}, or {@code null}
     */
    public static String normalize(String role) {
        if (role == null) {
            return null;
        }
        return switch (role.toLowerCase(java.util.Locale.ROOT)) {
            case ADMIN -> ADMIN;
            case STUDENT -> STUDENT;
            case COUNSELOR -> COUNSELOR;
            default -> null;
        };
    }

    /**
     * Reports whether a bearer token carries the admin role.
     *
     * @param jwt the bearer token (may be {@code null})
     * @return {@code true} when the token's role is {@link #ADMIN}
     */
    public static boolean isAdmin(Jwt jwt) {
        return jwt != null && ADMIN.equalsIgnoreCase(jwt.getClaimAsString(CLAIM));
    }
}
