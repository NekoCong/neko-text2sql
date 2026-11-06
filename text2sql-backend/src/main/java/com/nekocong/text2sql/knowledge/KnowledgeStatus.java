package com.nekocong.text2sql.knowledge;

/** Lifecycle of a knowledge item: persisted → parsed → chunked → embedded → retrievable. */
public enum KnowledgeStatus {
    /** Persisted, waiting for the async worker. */
    PENDING,
    /** The document is being parsed. */
    PARSING,
    /** Parsed and chunked, vectors are being generated. */
    EMBEDDING,
    /** Chunks and vectors are retrievable. */
    READY,
    /** A stage failed; see {@link FailureStage} and the failure reason. */
    FAILED
}
