package com.nekocong.text2sql.sql;

/**
 * Thrown when executing a (validated) SQL statement fails.
 */
public class SqlExecutionException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public SqlExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
