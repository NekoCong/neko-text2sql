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
CREATE TABLE IF NOT EXISTS datasource_access (
    role          VARCHAR(32) NOT NULL,
    datasource_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (role, datasource_id)
);
