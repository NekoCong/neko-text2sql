package com.nekocong.text2sql.schema;

/**
 * Thrown when schema introspection against a datasource fails.
 */
public class SchemaIntrospectionException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public SchemaIntrospectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
