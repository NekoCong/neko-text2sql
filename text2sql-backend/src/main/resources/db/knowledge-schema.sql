-- Knowledge base schema (PostgreSQL 16 + pgvector).
--
-- Logical three collections (METADATA / BUSINESS / AGENT) share one physical schema; isolation is
-- enforced by the type + scope columns on every query. Chunks never span pages, so each chunk has a
-- single page number and one bounding box (normalized to 0-1000 by the parser).
--
-- Executed by docker-compose on an empty pgvector data directory, and (idempotently) by
-- KnowledgeSchemaInitializer when the application starts with text2sql.knowledge.enabled=true.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS knowledge_item (
    id               VARCHAR(64)  PRIMARY KEY,
    knowledge_type   VARCHAR(16)  NOT NULL,
    scope            VARCHAR(16)  NOT NULL,
    datasource_id    VARCHAR(64),
    agent_id         VARCHAR(64),
    item_key         VARCHAR(512) NOT NULL,
    title            VARCHAR(512) NOT NULL,
    table_name       VARCHAR(128),
    content          TEXT,
    payload          JSONB,
    status           VARCHAR(16)  NOT NULL,
    failure_stage    VARCHAR(16),
    failure_reason   TEXT,
    embedding_model  VARCHAR(128),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uk_knowledge_item_key UNIQUE (item_key)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_item_scope
    ON knowledge_item (knowledge_type, datasource_id, agent_id, status);

CREATE INDEX IF NOT EXISTS idx_knowledge_item_table
    ON knowledge_item (datasource_id, table_name);

CREATE TABLE IF NOT EXISTS knowledge_document (
    id                    VARCHAR(64)  PRIMARY KEY,
    item_id               VARCHAR(64)  NOT NULL,
    source_type           VARCHAR(16)  NOT NULL,
    source_url            TEXT,
    filename              VARCHAR(512) NOT NULL,
    mime_type             VARCHAR(255) NOT NULL,
    size_bytes            BIGINT       NOT NULL DEFAULT 0,
    sha256                VARCHAR(64),
    object_key            VARCHAR(1024) NOT NULL,
    derived_prefix        VARCHAR(1024),
    page_count            INTEGER,
    parse_engine          VARCHAR(64),
    parse_engine_version  VARCHAR(64),
    parse_status          VARCHAR(16)  NOT NULL,
    parse_failure_reason  TEXT,
    fetched_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_knowledge_document_item UNIQUE (item_id)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_document_sha ON knowledge_document (sha256);

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id           VARCHAR(64)  PRIMARY KEY,
    item_id      VARCHAR(64)  NOT NULL,
    document_id  VARCHAR(64),
    chunk_index  INTEGER      NOT NULL,
    content      TEXT         NOT NULL,
    page_no      INTEGER,
    bbox         INTEGER[],
    char_start   INTEGER,
    char_end     INTEGER,
    quote        TEXT,
    embedding    vector(1024),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_knowledge_chunk_order UNIQUE (item_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_item ON knowledge_chunk (item_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_document ON knowledge_chunk (document_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_embedding
    ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);

CREATE TABLE IF NOT EXISTS knowledge_term_mapping (
    id             VARCHAR(64)  PRIMARY KEY,
    datasource_id  VARCHAR(64)  NOT NULL,
    term           VARCHAR(255) NOT NULL,
    target_type    VARCHAR(16)  NOT NULL,
    table_name     VARCHAR(128) NOT NULL,
    column_name    VARCHAR(128),
    description    TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_knowledge_term UNIQUE (datasource_id, term)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_term_datasource ON knowledge_term_mapping (datasource_id);
