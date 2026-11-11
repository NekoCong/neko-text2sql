package com.nekocong.text2sql.knowledge.store;

/** A readable vector-store failure (unreachable database, dimension mismatch, timeout, ...). */
public class VectorStoreException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message a user-readable reason
     */
    public VectorStoreException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message a user-readable reason
     * @param cause the underlying failure
     */
    public VectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
