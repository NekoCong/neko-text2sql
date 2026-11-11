-- 应用库种子数据：智慧校园演示账号、数据范围映射、数据源授权与示例会话。
--
-- 演示账号（开发/演示环境，任何真实使用前务必更换）：
--   admin       / admin123      角色 admin      全量可见，同时可维护数据源白名单
--   student01   / student123    角色 student    只能看到自己（张伟，1 班）
--   student02   / student123    角色 student    只能看到自己（李娜，1 班）
--   student03   / student123    角色 student    只能看到自己（王强，2 班）
--   counselor01 / counselor123  角色 counselor  可看到 1 班与 2 班
--
-- 数据范围的语义（见 openspec/changes/campus-data-permission/specs/data-permission/spec.md）：
--   学生   -> class_id + student_id + student_no 各一条，用于把查询收敛到本人
--   辅导员 -> 每条 class_id 一行，可覆盖多个班级
--   管理员 -> 不写任何 user_scope 行，即全量
--
-- 业务库 id（student_id）在种子中固定，保证演示数据可被逐条核对；真实系统若业务主键会变化，
-- 应改为按 student_no 在请求期解析（见 design.md 的风险项）。
--
-- 只在空数据目录执行（docker compose init）。要重新播种：docker compose down -v。
--
-- 重要：在任何数据语句之前先固定连接字符集，否则 MySQL 容器的 mysql 客户端可能按 latin1
-- 解释下面的 UTF-8 字节，中文会变成双重编码。
SET NAMES utf8mb4;

-- --- 账号 ---------------------------------------------------------------------------------------
INSERT IGNORE INTO app_user (id, username, password_hash, display_name, role, created_at) VALUES
    ('seed-user-admin',       'admin',       '$2a$10$CyF0X5/O9C8iH.B/CqTuveiWyGFofEgFWzn5Y0vW8XtkN1QEFDH5y', '系统管理员', 'admin',     1792454400000),
    ('seed-user-student01',   'student01',   '$2a$10$HhGCCvK2Wn4ts4DCM6kREO3gjbQax9MBY507RyNcuywmKv10Fvsfu', '张伟',       'student',   1792454401000),
    ('seed-user-student02',   'student02',   '$2a$10$HhGCCvK2Wn4ts4DCM6kREO3gjbQax9MBY507RyNcuywmKv10Fvsfu', '李娜',       'student',   1792454402000),
    ('seed-user-student03',   'student03',   '$2a$10$HhGCCvK2Wn4ts4DCM6kREO3gjbQax9MBY507RyNcuywmKv10Fvsfu', '王强',       'student',   1792454403000),
    ('seed-user-counselor01', 'counselor01', '$2a$10$6jPFACYIlLsps.n.aZSWoe.Ro40GaglrRSSHjt0/B.qYTedcsYs7e', '李辅导员',   'counselor', 1792454404000);

-- --- 数据范围映射 -------------------------------------------------------------------------------
INSERT IGNORE INTO user_scope (user_id, scope_type, scope_value) VALUES
    ('seed-user-student01',   'class_id',   '1'),
    ('seed-user-student01',   'student_id', '1'),
    ('seed-user-student01',   'student_no', '2023001'),
    ('seed-user-student02',   'class_id',   '1'),
    ('seed-user-student02',   'student_id', '2'),
    ('seed-user-student02',   'student_no', '2023002'),
    ('seed-user-student03',   'class_id',   '2'),
    ('seed-user-student03',   'student_id', '3'),
    ('seed-user-student03',   'student_no', '2023003'),
    ('seed-user-counselor01', 'class_id',   '1'),
    ('seed-user-counselor01', 'class_id',   '2');

-- --- 数据源白名单（管理员无需记录） -------------------------------------------------------------
INSERT IGNORE INTO datasource_access (role, datasource_id) VALUES
    ('student',   'campus'),
    ('counselor', 'campus');

-- --- 可配置的访问控制（角色 / 权限 / 数据范围） --------------------------------------------------
-- 内置角色与权限目录：等价于原 TablePolicy 的硬编码行为，迁移后改由数据驱动。
-- 管理员通过 access.bypass_data_scope 跳过数据范围，因此不需要任何数据范围行。
INSERT IGNORE INTO access_role (id, code, name, description, parent_id, built_in, enabled, created_at, updated_at) VALUES
    ('seed-role-admin',     'admin',     '管理员', '内置角色：全部权限，跳过数据范围过滤', NULL, TRUE, TRUE, 1792454400000, 1792454400000),
    ('seed-role-student',   'student',   '学生',   '内置角色：只能查询本人数据',           NULL, TRUE, TRUE, 1792454400000, 1792454400000),
    ('seed-role-counselor', 'counselor', '辅导员', '内置角色：可查询所带班级的数据',       NULL, TRUE, TRUE, 1792454400000, 1792454400000);

INSERT IGNORE INTO access_permission (id, permission_key, module, action, description, built_in) VALUES
    ('seed-perm-chat-use',                'chat.use',                'chat',       'use',                '使用对话与问数',           TRUE),
    ('seed-perm-datasource-read',         'datasource.read',         'datasource', 'read',               '查看数据源',               TRUE),
    ('seed-perm-datasource-write',        'datasource.write',        'datasource', 'write',              '维护数据源',               TRUE),
    ('seed-perm-model-manage',            'model.manage',            'model',      'manage',             '维护模型配置',             TRUE),
    ('seed-perm-access-manage',           'access.manage',           'access',     'manage',             '维护角色与访问控制',       TRUE),
    ('seed-perm-user-read',               'user.read',               'user',       'read',               '查看用户',                 TRUE),
    ('seed-perm-user-manage',             'user.manage',             'user',       'manage',             '维护用户',                 TRUE),
    ('seed-perm-audit-read',              'audit.read',              'audit',      'read',               '查看审计日志',             TRUE),
    ('seed-perm-access-bypass-data-scope', 'access.bypass_data_scope', 'access',    'bypass_data_scope', '跳过数据范围过滤（管理员）', TRUE);

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

-- 账号 -> 角色：与上面的 app_user.role 保持一一对应，便于逐条核对。
INSERT IGNORE INTO access_user_role (user_id, role_id, created_at) VALUES
    ('seed-user-admin',       'seed-role-admin',     1792454400000),
    ('seed-user-student01',   'seed-role-student',   1792454400000),
    ('seed-user-student02',   'seed-role-student',   1792454400000),
    ('seed-user-student03',   'seed-role-student',   1792454400000),
    ('seed-user-counselor01', 'seed-role-counselor', 1792454400000);

-- 角色 -> 数据源白名单（管理员跳过检查，无需记录）。
INSERT IGNORE INTO access_role_datasource (role_id, datasource_id) VALUES
    ('seed-role-student',   'campus'),
    ('seed-role-counselor', 'campus');

-- 数据范围：等价于原 TablePolicy。
--   学生   -> class/student/score/attendance 收敛到本人，course 全量
--   辅导员 -> class 收敛到所带班级，student/score/attendance 按 class_id 收敛，course 全量
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

-- --- 示例会话 -----------------------------------------------------------------------------------
INSERT IGNORE INTO conversation (id, user_id, title, datasource_id, model_config_id, created_at, updated_at) VALUES
    ('seed-conv-student01-1',   'seed-user-student01',   '我的数学成绩',     'campus', NULL, 1792458000000, 1792461600000),
    ('seed-conv-student01-2',   'seed-user-student01',   '我的考勤记录',     'campus', NULL, 1792465200000, 1792465200000),
    ('seed-conv-student02-1',   'seed-user-student02',   '我的各科成绩',     'campus', NULL, 1792468800000, 1792472400000),
    ('seed-conv-counselor01-1', 'seed-user-counselor01', '一班各科平均分',   'campus', NULL, 1792476000000, 1792476000000),
    ('seed-conv-admin-1',       'seed-user-admin',       '各班人数统计',     'campus', NULL, 1792479600000, 1792479600000);

-- --- 示例消息（一条完整问答，打开会话即可看到 SQL 与结果） --------------------------------------
INSERT IGNORE INTO message (id, conversation_id, role, content, sql_text, chart_json, rows_json, created_at) VALUES
    ('seed-msg-student01-1-user', 'seed-conv-student01-1', 'user', '我的数学成绩是多少？', NULL, NULL, NULL, 1792458000000),
    ('seed-msg-student01-1-bot',  'seed-conv-student01-1', 'assistant', '你的数学成绩是 92.0 分。',
     'SELECT s.name, c.name AS course_name, sc.score FROM score sc JOIN student s ON s.id = sc.student_id JOIN course c ON c.id = sc.course_id WHERE s.student_no = ''2023001'' AND c.code = ''MATH101''',
     '{"type":"TABLE","xField":"course_name","yField":"score","title":"我的数学成绩"}',
     '[{"name":"张伟","course_name":"数学","score":92.0}]', 1792461600000);
