package com.nekocong.text2sql.knowledge.service;

/** A chunk cannot be produced with the citation metadata the specification requires. */
public class ChunkingException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message a user-readable reason
     */
    public ChunkingException(String message) {
        super(message);
    }
}
