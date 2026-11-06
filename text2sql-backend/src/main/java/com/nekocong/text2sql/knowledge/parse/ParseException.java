package com.nekocong.text2sql.knowledge.parse;

/** A readable parse failure (unsupported format, engine unavailable, empty result, ...). */
public class ParseException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message a user-readable reason
     */
    public ParseException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message a user-readable reason
     * @param cause the underlying failure
     */
    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
