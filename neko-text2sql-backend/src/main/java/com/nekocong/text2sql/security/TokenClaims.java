package com.nekocong.text2sql.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Identity helpers that read a verified token without deciding authorization.
 *
 * <p>A valid JWT only proves identity; authorization (roles, permissions, data scopes) comes from
 * the application database on every request.
 */
public final class TokenClaims {

    /** The claim carrying the account's session version. */
    public static final String VERSION = "ver";

    private TokenClaims() {}

    /**
     * Reads the token's session version, tolerating the numeric types a JSON decoder may produce.
     *
     * @param jwt the bearer token (may be {@code null})
     * @return the version, or {@code null} when absent or unparsable (callers must fail closed)
     */
    public static Integer tokenVersion(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        Object value = jwt.getClaim(VERSION);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
