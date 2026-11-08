package com.nekocong.text2sql.access;

/**
 * The authenticated account row needed to resolve access.
 *
 * @param id the user id
 * @param username the login name
 * @param legacyRole the legacy role string (display/backfill only)
 * @param enabled whether the account may sign in
 * @param tokenVersion the current token version
 */
public record AccountStatus(
        String id, String username, String legacyRole, boolean enabled, int tokenVersion) {
}
