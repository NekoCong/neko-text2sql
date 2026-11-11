package com.nekocong.text2sql.sql;

/**
 * Thrown when a generated statement is rejected by {@link SqlSafetyValidator}.
 */
public class SqlSafetyException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message the reason the SQL was rejected
     */
    public SqlSafetyException(String message) {
        super(message);
    }
}
