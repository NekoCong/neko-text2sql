-- Application metadata schema: users, model configs, conversations and messages.
-- Portable across MySQL 8 and H2 (MODE=MySQL) so tests run without a MySQL server.
-- Ids are app-generated UUID strings (R2DBC has no portable generated-key API), and timestamps are
-- epoch-millis BIGINTs for consistent, precise ordering.
-- Executed by docker-compose init (MySQL) and, for tests, via spring.sql.init (H2).

CREATE TABLE IF NOT EXISTS app_user (
    id            VARCHAR(64)  PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100),
    -- Role name: 'admin' sees everything, 'student' is limited to its own rows, 'counselor' to the
    -- classes it manages. Assigned out of band (seed SQL or a direct update); self-service
    -- registration always inserts 'student'.
    role          VARCHAR(32)  NOT NULL DEFAULT 'student',
    -- Account status and session invalidation. A disabled account cannot log in, and bumping
    -- token_version rejects every token issued before the change (disable, re-enable, password
    -- reset). The legacy `role` column above is kept for display and for the migration backfill.
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    token_version INTEGER      NOT NULL DEFAULT 0,
    created_at    BIGINT       NOT NULL,
    CONSTRAINT uk_user_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS model_config (
    id         VARCHAR(64)  PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    provider   VARCHAR(32)  NOT NULL,
    model      VARCHAR(128) NOT NULL,
    api_key    VARCHAR(512),
    base_url   VARCHAR(512),
    is_default BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at BIGINT       NOT NULL
);

CREATE TABLE IF NOT EXISTS conversation (
    id              VARCHAR(64)  PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    title           VARCHAR(255) NOT NULL,
    datasource_id   VARCHAR(64),
    model_config_id VARCHAR(64),
    created_at      BIGINT       NOT NULL,
    updated_at      BIGINT       NOT NULL
);

CREATE TABLE IF NOT EXISTS message (
    id              VARCHAR(64) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    role            VARCHAR(16) NOT NULL,
    content         TEXT,
    sql_text        TEXT,
    chart_json      TEXT,
    rows_json       TEXT,
    citations_json  TEXT,
    created_at      BIGINT      NOT NULL
);

-- Principal attributes used by the data-permission layer. Deliberately stored in the application
-- database (the identity side) rather than the analysed business database: a query must never have
-- to read the protected data source in order to learn who the caller is.
--
--   scope_type = 'class_id'    -> a class the principal may see (counselor: many rows)
--   scope_type = 'student_id'  -> the principal's own business-database student id
--   scope_type = 'student_no'  -> the principal's own student number (used for self-scoped queries)
CREATE TABLE IF NOT EXISTS user_scope (
    user_id     VARCHAR(64) NOT NULL,
    scope_type  VARCHAR(32) NOT NULL,
    scope_value VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, scope_type, scope_value)
);

-- Which datasource each role may query. Admins are unrestricted and therefore need no rows here.
-- Legacy matrix kept for backward compatibility; access_role_datasource is the configurable source.
CREATE TABLE IF NOT EXISTS datasource_access (
    role          VARCHAR(32) NOT NULL,
    datasource_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (role, datasource_id)
);

-- --- Configurable access control -------------------------------------------------------------
-- Roles/users are disabled rather than deleted, so no database-level foreign keys are declared
-- anywhere below: references are validated in the service layer. Every resolver lookup is a
-- primary-key or unique-key prefix, so this file deliberately declares no standalone
-- CREATE INDEX statements (H2 tests re-run it against the same named database, and MySQL has no
-- CREATE INDEX IF NOT EXISTS); secondary indexes are created by db/migration/*.sql instead.

-- A role is a named permission bundle. `code` is immutable after creation; `parent_id` gives
-- single-parent inheritance (cycles are rejected in the application).
CREATE TABLE IF NOT EXISTS access_role (
    id          VARCHAR(64)  PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(512),
    parent_id   VARCHAR(64),
    built_in    BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  BIGINT       NOT NULL,
    updated_at  BIGINT       NOT NULL,
    CONSTRAINT uk_access_role_code UNIQUE (code)
);

-- Permission catalog, keyed by dotted module.action strings such as `chat.use`.
CREATE TABLE IF NOT EXISTS access_permission (
    id             VARCHAR(64)  PRIMARY KEY,
    permission_key VARCHAR(128) NOT NULL,
    module         VARCHAR(64)  NOT NULL,
    action         VARCHAR(64)  NOT NULL,
    description    VARCHAR(512),
    built_in       BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_access_permission_key UNIQUE (permission_key)
);

CREATE TABLE IF NOT EXISTS access_role_permission (
    role_id       VARCHAR(64) NOT NULL,
    permission_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS access_user_role (
    user_id    VARCHAR(64) NOT NULL,
    role_id    VARCHAR(64) NOT NULL,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS access_role_datasource (
    role_id       VARCHAR(64) NOT NULL,
    datasource_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id, datasource_id)
);

-- Per-role, per-table row policy.
--   policy = 'ALL'    -> no restriction (column_name / principal_attribute are NULL)
--   policy = 'FILTER' -> column_name IN/= principal_attribute
--   policy = 'DENY'   -> table denied (wins over every other policy)
CREATE TABLE IF NOT EXISTS access_data_scope (
    id                  VARCHAR(64)  PRIMARY KEY,
    role_id             VARCHAR(64)  NOT NULL,
    datasource_id       VARCHAR(64)  NOT NULL,
    table_name          VARCHAR(128) NOT NULL,
    policy              VARCHAR(16)  NOT NULL,
    column_name         VARCHAR(128),
    principal_attribute VARCHAR(32),
    created_at          BIGINT       NOT NULL,
    updated_at          BIGINT       NOT NULL,
    CONSTRAINT uk_access_data_scope UNIQUE (role_id, datasource_id, table_name)
);

-- Access-control audit trail. JSON payloads are TEXT (not MySQL JSON) so H2 runs the same DDL,
-- and must never contain password hashes or token material.
CREATE TABLE IF NOT EXISTS access_audit (
    id          VARCHAR(64)  PRIMARY KEY,
    actor_id    VARCHAR(64)  NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    entity_type VARCHAR(32)  NOT NULL,
    entity_id   VARCHAR(64),
    before_json TEXT,
    after_json  TEXT,
    result      VARCHAR(16)  NOT NULL,
    reason      VARCHAR(512),
    created_at  BIGINT       NOT NULL
);
