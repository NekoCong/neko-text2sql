package com.nekocong.text2sql.security;

/**
 * Raised when a query cannot be executed under the caller's data scope: the statement references a
 * table outside the whitelist, or it cannot be rewritten safely.
 *
 * <p>This is a fail-closed signal: callers must reject the query rather than execute the original
 * statement.
 */
public class SqlPermissionException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message a user-readable reason
     */
    public SqlPermissionException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message a user-readable reason
     * @param cause the underlying failure
     */
    public SqlPermissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
