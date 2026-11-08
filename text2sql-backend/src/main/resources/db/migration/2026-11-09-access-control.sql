-- 2026-11-09 可配置的访问控制：增量迁移（仅 MySQL 8）。
--
-- 适用场景：已有数据卷的应用库。db/app-schema.sql 只在空数据目录执行，因此老库缺少
-- app_user.enabled / app_user.token_version 以及七张访问控制表，需要本脚本补齐。
--
-- 幂等：可重复执行。
--   1. 列存在性用 information_schema.columns 判断后再 PREPARE/EXECUTE（MySQL 8 没有
--      ADD COLUMN IF NOT EXISTS），与 deploy/app-migrate.sql 的写法一致。
--   2. 建表使用 CREATE TABLE IF NOT EXISTS。
--   3. 索引存在性用 information_schema.statistics 判断（MySQL 8 没有 CREATE INDEX IF NOT EXISTS）。
--   4. 回填使用 INSERT IGNORE + 主键/唯一键，重复执行不会产生重复行。
--
-- 不删除、不修改任何既有列与表：app_user.role、user_scope、datasource_access 原样保留，
-- 供旧的展示逻辑与后续回滚使用。

-- --- 1. app_user 新增列 --------------------------------------------------------------------------
SET @sql = (
    SELECT IF(
        (SELECT COUNT(*)
           FROM information_schema.columns
          WHERE table_schema = DATABASE()
            AND table_name = 'app_user'
            AND column_name = 'enabled') = 0,
        'ALTER TABLE app_user ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE',
        'SELECT ''app_user.enabled already present'' AS migration'
    )
);
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

SET @sql = (
    SELECT IF(
        (SELECT COUNT(*)
           FROM information_schema.columns
          WHERE table_schema = DATABASE()
            AND table_name = 'app_user'
            AND column_name = 'token_version') = 0,
        'ALTER TABLE app_user ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0',
        'SELECT ''app_user.token_version already present'' AS migration'
    )
);
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

-- --- 2. 新建表（与 db/app-schema.sql 保持一致，只带内联主键/唯一键） --------------------------------
-- 无数据库级外键：角色与用户只禁用不删除，引用由服务层校验。
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

-- --- 3. 二级索引（仅在缺失时创建） ---------------------------------------------------------------
-- 解析器的主查询走主键/唯一键前缀，这里补的是后台管理与按角色反查的索引。

SET @sql = (
    SELECT IF(
        (SELECT COUNT(*)
           FROM information_schema.statistics
          WHERE table_schema = DATABASE()
            AND table_name = 'access_role'
            AND index_name = 'idx_access_role_parent') = 0,
        'CREATE INDEX idx_access_role_parent ON access_role (parent_id)',
        'SELECT ''idx_access_role_parent already present'' AS migration'
    )
);
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

SET @sql = (
    SELECT IF(
        (SELECT COUNT(*)
           FROM information_schema.statistics
          WHERE table_schema = DATABASE()
            AND table_name = 'access_role_permission'
            AND index_name = 'idx_access_role_permission_perm') = 0,
        'CREATE INDEX idx_access_role_permission_perm ON access_role_permission (permission_id)',
        'SELECT ''idx_access_role_permission_perm already present'' AS migration'
    )
);
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

SET @sql = (
    SELECT IF(
        (SELECT COUNT(*)
           FROM information_schema.statistics
          WHERE table_schema = DATABASE()
            AND table_name = 'access_user_role'
            AND index_name = 'idx_access_user_role_role') = 0,
        'CREATE INDEX idx_access_user_role_role ON access_user_role (role_id)',
        'SELECT ''idx_access_user_role_role already present'' AS migration'
    )
);
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

SET @sql = (
    SELECT IF(
        (SELECT COUNT(*)
           FROM information_schema.statistics
          WHERE table_schema = DATABASE()
            AND table_name = 'access_role_datasource'
            AND index_name = 'idx_access_role_datasource_ds') = 0,
        'CREATE INDEX idx_access_role_datasource_ds ON access_role_datasource (datasource_id)',
        'SELECT ''idx_access_role_datasource_ds already present'' AS migration'
    )
);
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

SET @sql = (
    SELECT IF(
        (SELECT COUNT(*)
           FROM information_schema.statistics
          WHERE table_schema = DATABASE()
            AND table_name = 'access_data_scope'
            AND index_name = 'idx_access_data_scope_lookup') = 0,
        'CREATE INDEX idx_access_data_scope_lookup ON access_data_scope (datasource_id, table_name)',
        'SELECT ''idx_access_data_scope_lookup already present'' AS migration'
    )
);
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

SET @sql = (
    SELECT IF(
        (SELECT COUNT(*)
           FROM information_schema.statistics
          WHERE table_schema = DATABASE()
            AND table_name = 'access_audit'
            AND index_name = 'idx_access_audit_created') = 0,
        'CREATE INDEX idx_access_audit_created ON access_audit (created_at)',
        'SELECT ''idx_access_audit_created already present'' AS migration'
    )
);
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

SET @sql = (
    SELECT IF(
        (SELECT COUNT(*)
           FROM information_schema.statistics
          WHERE table_schema = DATABASE()
            AND table_name = 'access_audit'
            AND index_name = 'idx_access_audit_entity') = 0,
        'CREATE INDEX idx_access_audit_entity ON access_audit (entity_type, entity_id)',
        'SELECT ''idx_access_audit_entity already present'' AS migration'
    )
);
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

SET @sql = (
    SELECT IF(
        (SELECT COUNT(*)
           FROM information_schema.statistics
          WHERE table_schema = DATABASE()
            AND table_name = 'access_audit'
            AND index_name = 'idx_access_audit_actor') = 0,
        'CREATE INDEX idx_access_audit_actor ON access_audit (actor_id)',
        'SELECT ''idx_access_audit_actor already present'' AS migration'
    )
);
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

-- --- 4. 回填内置角色、权限目录、角色权限、数据范围 ----------------------------------------------
-- 与 db/app-data.sql 的种子完全一致（固定 id，因此 INSERT IGNORE 幂等）。

INSERT IGNORE INTO access_role (id, code, name, description, parent_id, built_in, enabled, created_at, updated_at) VALUES
    ('seed-role-admin',     'admin',     '管理员', '内置角色：全部权限，跳过数据范围过滤', NULL, TRUE, TRUE, 1792454400000, 1792454400000),
    ('seed-role-student',   'student',   '学生',   '内置角色：只能查询本人数据',           NULL, TRUE, TRUE, 1792454400000, 1792454400000),
    ('seed-role-counselor', 'counselor', '辅导员', '内置角色：可查询所带班级的数据',       NULL, TRUE, TRUE, 1792454400000, 1792454400000);

INSERT IGNORE INTO access_permission (id, permission_key, module, action, description, built_in) VALUES
    ('seed-perm-chat-use',                 'chat.use',                 'chat',       'use',                '使用对话与问数',             TRUE),
    ('seed-perm-datasource-read',          'datasource.read',          'datasource', 'read',               '查看数据源',                 TRUE),
    ('seed-perm-datasource-write',         'datasource.write',         'datasource', 'write',              '维护数据源',                 TRUE),
    ('seed-perm-model-manage',             'model.manage',             'model',      'manage',             '维护模型配置',               TRUE),
    ('seed-perm-access-manage',            'access.manage',            'access',     'manage',             '维护角色与访问控制',         TRUE),
    ('seed-perm-user-read',                'user.read',                'user',       'read',               '查看用户',                   TRUE),
    ('seed-perm-user-manage',              'user.manage',              'user',       'manage',             '维护用户',                   TRUE),
    ('seed-perm-audit-read',               'audit.read',               'audit',      'read',               '查看审计日志',               TRUE),
    ('seed-perm-access-bypass-data-scope', 'access.bypass_data_scope', 'access',     'bypass_data_scope',  '跳过数据范围过滤（管理员）', TRUE);

INSERT IGNORE INTO access_role_permission (role_id, permission_id) VALUES
    ('seed-role-admin',     'seed-perm-chat-use'),
    ('seed-role-admin',     'seed-perm-datasource-read'),
    ('seed-role-admin',     'seed-perm-datasource-write'),
    ('seed-role-admin',     'seed-perm-model-manage'),
    ('seed-role-admin',     'seed-perm-access-manage'),
    ('seed-role-admin',     'seed-perm-user-read'),
    ('seed-role-admin',     'seed-perm-user-manage'),
    ('seed-role-admin',     'seed-perm-audit-read'),
    ('seed-role-admin',     'seed-perm-access-bypass-data-scope'),
    ('seed-role-student',   'seed-perm-chat-use'),
    ('seed-role-student',   'seed-perm-datasource-read'),
    ('seed-role-counselor', 'seed-perm-chat-use'),
    ('seed-role-counselor', 'seed-perm-datasource-read');

INSERT IGNORE INTO access_data_scope (id, role_id, datasource_id, table_name, policy, column_name, principal_attribute, created_at, updated_at) VALUES
    ('seed-scope-student-class',        'seed-role-student',   'campus', 'class',      'FILTER', 'id',         'classIds',   1792454400000, 1792454400000),
    ('seed-scope-student-student',      'seed-role-student',   'campus', 'student',    'FILTER', 'student_no', 'studentNo',  1792454400000, 1792454400000),
    ('seed-scope-student-score',        'seed-role-student',   'campus', 'score',      'FILTER', 'student_id', 'studentId',  1792454400000, 1792454400000),
    ('seed-scope-student-attendance',   'seed-role-student',   'campus', 'attendance', 'FILTER', 'student_id', 'studentId',  1792454400000, 1792454400000),
    ('seed-scope-student-course',       'seed-role-student',   'campus', 'course',     'ALL',    NULL,         NULL,         1792454400000, 1792454400000),
    ('seed-scope-counselor-class',      'seed-role-counselor', 'campus', 'class',      'FILTER', 'id',         'classIds',   1792454400000, 1792454400000),
    ('seed-scope-counselor-student',    'seed-role-counselor', 'campus', 'student',    'FILTER', 'class_id',   'classIds',   1792454400000, 1792454400000),
    ('seed-scope-counselor-score',      'seed-role-counselor', 'campus', 'score',      'FILTER', 'class_id',   'classIds',   1792454400000, 1792454400000),
    ('seed-scope-counselor-attendance', 'seed-role-counselor', 'campus', 'attendance', 'FILTER', 'class_id',   'classIds',   1792454400000, 1792454400000),
    ('seed-scope-counselor-course',     'seed-role-counselor', 'campus', 'course',     'ALL',    NULL,         NULL,         1792454400000, 1792454400000);

-- --- 5. 回填既有数据（幂等，不改动原表） ---------------------------------------------------------
-- 账号 -> 角色：沿用 app_user.role 的取值，按角色 code 关联；未知角色值自动跳过。
INSERT IGNORE INTO access_user_role (user_id, role_id, created_at)
SELECT u.id, r.id, 1792454400000
  FROM app_user u
  JOIN access_role r ON r.code = u.role;

-- 角色 -> 数据源：沿用 datasource_access 的授权矩阵；管理员本来就没有行，无需特殊处理。
INSERT IGNORE INTO access_role_datasource (role_id, datasource_id)
SELECT r.id, d.datasource_id
  FROM datasource_access d
  JOIN access_role r ON r.code = d.role;
