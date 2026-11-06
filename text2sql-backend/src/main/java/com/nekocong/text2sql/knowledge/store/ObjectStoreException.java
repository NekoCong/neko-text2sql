package com.nekocong.text2sql.knowledge.store;

/** A readable object-storage failure (missing object, unreachable store, bad credentials, ...). */
public class ObjectStoreException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message a user-readable reason
     */
    public ObjectStoreException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message a user-readable reason
     * @param cause the underlying failure
     */
    public ObjectStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
