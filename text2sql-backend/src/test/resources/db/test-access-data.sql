-- Minimal configurable-access-control seed for the H2 test database.
--
-- The production seed (db/app-data.sql) is MySQL-only (INSERT IGNORE), so the test suite gets its own
-- H2-friendly rows: the three built-in roles, the nine built-in permission keys, role-permission
-- grants (admin: all; student/counselor: chat.use + datasource.read) and role-datasource grants
-- (student/counselor: campus). Ids are deterministic so tests can reason about them.
--
-- The named H2 database is shared across Spring contexts within one Maven run, so every statement is
-- written idempotently (INSERT ... SELECT ... WHERE NOT EXISTS) and only adds rows that are missing.
--
-- No app users and no conversations are inserted here: tests create their own accounts.

INSERT INTO access_role (id, code, name, description, parent_id, built_in, enabled, created_at, updated_at)
SELECT 'test-role-admin', 'admin', '管理员', '内置角色：全部权限，跳过数据范围过滤', NULL, TRUE, TRUE, 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_role WHERE code = 'admin');
INSERT INTO access_role (id, code, name, description, parent_id, built_in, enabled, created_at, updated_at)
SELECT 'test-role-student', 'student', '学生', '内置角色：只能查询本人数据', NULL, TRUE, TRUE, 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_role WHERE code = 'student');
INSERT INTO access_role (id, code, name, description, parent_id, built_in, enabled, created_at, updated_at)
SELECT 'test-role-counselor', 'counselor', '辅导员', '内置角色：可查询所带班级的数据', NULL, TRUE, TRUE, 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_role WHERE code = 'counselor');

INSERT INTO access_permission (id, permission_key, module, action, description, built_in)
SELECT 'test-perm-chat-use', 'chat.use', 'chat', 'use', '使用对话与问数', TRUE
WHERE NOT EXISTS (SELECT 1 FROM access_permission WHERE permission_key = 'chat.use');
INSERT INTO access_permission (id, permission_key, module, action, description, built_in)
SELECT 'test-perm-datasource-read', 'datasource.read', 'datasource', 'read', '查看数据源', TRUE
WHERE NOT EXISTS (SELECT 1 FROM access_permission WHERE permission_key = 'datasource.read');
INSERT INTO access_permission (id, permission_key, module, action, description, built_in)
SELECT 'test-perm-datasource-write', 'datasource.write', 'datasource', 'write', '维护数据源', TRUE
WHERE NOT EXISTS (SELECT 1 FROM access_permission WHERE permission_key = 'datasource.write');
INSERT INTO access_permission (id, permission_key, module, action, description, built_in)
SELECT 'test-perm-model-manage', 'model.manage', 'model', 'manage', '维护模型配置', TRUE
WHERE NOT EXISTS (SELECT 1 FROM access_permission WHERE permission_key = 'model.manage');
INSERT INTO access_permission (id, permission_key, module, action, description, built_in)
SELECT 'test-perm-access-manage', 'access.manage', 'access', 'manage', '维护角色与访问控制', TRUE
WHERE NOT EXISTS (SELECT 1 FROM access_permission WHERE permission_key = 'access.manage');
INSERT INTO access_permission (id, permission_key, module, action, description, built_in)
SELECT 'test-perm-user-read', 'user.read', 'user', 'read', '查看用户', TRUE
WHERE NOT EXISTS (SELECT 1 FROM access_permission WHERE permission_key = 'user.read');
INSERT INTO access_permission (id, permission_key, module, action, description, built_in)
SELECT 'test-perm-user-manage', 'user.manage', 'user', 'manage', '维护用户', TRUE
WHERE NOT EXISTS (SELECT 1 FROM access_permission WHERE permission_key = 'user.manage');
INSERT INTO access_permission (id, permission_key, module, action, description, built_in)
SELECT 'test-perm-audit-read', 'audit.read', 'audit', 'read', '查看审计日志', TRUE
WHERE NOT EXISTS (SELECT 1 FROM access_permission WHERE permission_key = 'audit.read');
INSERT INTO access_permission (id, permission_key, module, action, description, built_in)
SELECT 'test-perm-access-bypass-data-scope', 'access.bypass_data_scope', 'access', 'bypass_data_scope', '跳过数据范围过滤（管理员）', TRUE
WHERE NOT EXISTS (SELECT 1 FROM access_permission WHERE permission_key = 'access.bypass_data_scope');

INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-admin', 'test-perm-chat-use'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-admin' AND permission_id = 'test-perm-chat-use');
INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-admin', 'test-perm-datasource-read'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-admin' AND permission_id = 'test-perm-datasource-read');
INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-admin', 'test-perm-datasource-write'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-admin' AND permission_id = 'test-perm-datasource-write');
INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-admin', 'test-perm-model-manage'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-admin' AND permission_id = 'test-perm-model-manage');
INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-admin', 'test-perm-access-manage'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-admin' AND permission_id = 'test-perm-access-manage');
INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-admin', 'test-perm-user-read'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-admin' AND permission_id = 'test-perm-user-read');
INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-admin', 'test-perm-user-manage'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-admin' AND permission_id = 'test-perm-user-manage');
INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-admin', 'test-perm-audit-read'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-admin' AND permission_id = 'test-perm-audit-read');
INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-admin', 'test-perm-access-bypass-data-scope'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-admin' AND permission_id = 'test-perm-access-bypass-data-scope');
INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-student', 'test-perm-chat-use'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-student' AND permission_id = 'test-perm-chat-use');
INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-student', 'test-perm-datasource-read'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-student' AND permission_id = 'test-perm-datasource-read');
INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-counselor', 'test-perm-chat-use'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-counselor' AND permission_id = 'test-perm-chat-use');
INSERT INTO access_role_permission (role_id, permission_id)
SELECT 'test-role-counselor', 'test-perm-datasource-read'
WHERE NOT EXISTS (SELECT 1 FROM access_role_permission WHERE role_id = 'test-role-counselor' AND permission_id = 'test-perm-datasource-read');

INSERT INTO access_role_datasource (role_id, datasource_id)
SELECT 'test-role-student', 'campus'
WHERE NOT EXISTS (SELECT 1 FROM access_role_datasource WHERE role_id = 'test-role-student' AND datasource_id = 'campus');
INSERT INTO access_role_datasource (role_id, datasource_id)
SELECT 'test-role-counselor', 'campus'
WHERE NOT EXISTS (SELECT 1 FROM access_role_datasource WHERE role_id = 'test-role-counselor' AND datasource_id = 'campus');

-- --- 数据范围（等价于原 TablePolicy 的硬编码行为） ---------------------------------------------
-- 学生   -> class/student/score/attendance 收敛到本人，course 全量
-- 辅导员 -> class 收敛到所带班级，student/score/attendance 按 class_id 收敛，course 全量
-- 管理员 -> 通过 access.bypass_data_scope 跳过，不写任何数据范围行
INSERT INTO access_data_scope (id, role_id, datasource_id, table_name, policy, column_name, principal_attribute, created_at, updated_at)
SELECT 'test-scope-student-class', 'test-role-student', 'campus', 'class', 'FILTER', 'id', 'classIds', 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_data_scope WHERE id = 'test-scope-student-class');
INSERT INTO access_data_scope (id, role_id, datasource_id, table_name, policy, column_name, principal_attribute, created_at, updated_at)
SELECT 'test-scope-student-student', 'test-role-student', 'campus', 'student', 'FILTER', 'student_no', 'studentNo', 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_data_scope WHERE id = 'test-scope-student-student');
INSERT INTO access_data_scope (id, role_id, datasource_id, table_name, policy, column_name, principal_attribute, created_at, updated_at)
SELECT 'test-scope-student-score', 'test-role-student', 'campus', 'score', 'FILTER', 'student_id', 'studentId', 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_data_scope WHERE id = 'test-scope-student-score');
INSERT INTO access_data_scope (id, role_id, datasource_id, table_name, policy, column_name, principal_attribute, created_at, updated_at)
SELECT 'test-scope-student-attendance', 'test-role-student', 'campus', 'attendance', 'FILTER', 'student_id', 'studentId', 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_data_scope WHERE id = 'test-scope-student-attendance');
INSERT INTO access_data_scope (id, role_id, datasource_id, table_name, policy, column_name, principal_attribute, created_at, updated_at)
SELECT 'test-scope-student-course', 'test-role-student', 'campus', 'course', 'ALL', NULL, NULL, 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_data_scope WHERE id = 'test-scope-student-course');
INSERT INTO access_data_scope (id, role_id, datasource_id, table_name, policy, column_name, principal_attribute, created_at, updated_at)
SELECT 'test-scope-counselor-class', 'test-role-counselor', 'campus', 'class', 'FILTER', 'id', 'classIds', 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_data_scope WHERE id = 'test-scope-counselor-class');
INSERT INTO access_data_scope (id, role_id, datasource_id, table_name, policy, column_name, principal_attribute, created_at, updated_at)
SELECT 'test-scope-counselor-student', 'test-role-counselor', 'campus', 'student', 'FILTER', 'class_id', 'classIds', 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_data_scope WHERE id = 'test-scope-counselor-student');
INSERT INTO access_data_scope (id, role_id, datasource_id, table_name, policy, column_name, principal_attribute, created_at, updated_at)
SELECT 'test-scope-counselor-score', 'test-role-counselor', 'campus', 'score', 'FILTER', 'class_id', 'classIds', 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_data_scope WHERE id = 'test-scope-counselor-score');
INSERT INTO access_data_scope (id, role_id, datasource_id, table_name, policy, column_name, principal_attribute, created_at, updated_at)
SELECT 'test-scope-counselor-attendance', 'test-role-counselor', 'campus', 'attendance', 'FILTER', 'class_id', 'classIds', 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_data_scope WHERE id = 'test-scope-counselor-attendance');
INSERT INTO access_data_scope (id, role_id, datasource_id, table_name, policy, column_name, principal_attribute, created_at, updated_at)
SELECT 'test-scope-counselor-course', 'test-role-counselor', 'campus', 'course', 'ALL', NULL, NULL, 1792454400000, 1792454400000
WHERE NOT EXISTS (SELECT 1 FROM access_data_scope WHERE id = 'test-scope-counselor-course');
