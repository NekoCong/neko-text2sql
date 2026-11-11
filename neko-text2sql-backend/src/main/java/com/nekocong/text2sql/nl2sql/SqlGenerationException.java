package com.nekocong.text2sql.nl2sql;

/**
 * Thrown when the model output cannot be turned into a SQL statement.
 */
public class SqlGenerationException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public SqlGenerationException(String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public SqlGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
