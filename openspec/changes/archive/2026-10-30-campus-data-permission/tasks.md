# Tasks

## 1. 校园数据集与应用库模型

- [x] 1.1 用校园域重写业务库种子：`neko-text2sql-backend/src/main/resources/db/schema.sql`（`class` / `course` / `student` / `score` / `attendance`，过滤列索引，删除电商四表）与 `db/data.sql`（至少 3 个班、5 名学生含一组同班与一组异班、成绩与考勤，`score/attendance.class_id` 与所属学生一致）。验证：`mvnw.cmd test -Dtest=SeedDataScriptTest` 通过，且 H2 seed 后能内省出五张表、无 `orders/customers` 表。
- [x] 1.2 扩展应用库 schema：`db/app-schema.sql` 新增 `user_scope(user_id, scope_type, scope_value)` 与 `datasource_access(role, datasource_id)`，并更新 `app_user.role` 注释为 `student|counselor|admin`。验证：测试 profile 启动时 H2 建表成功，新查询可读写两张表。
- [x] 1.3 重写 `db/app-data.sql`：移除 `alice/bob/admin` 旧账号与电商会话；种子 `student01/student02/student03/counselor01/admin`，写入对应 `user_scope`（学生含 `class_id`+`student_id`+`student_no`，辅导员两条 `class_id`）、`datasource_access`（学生/辅导员 → `campus`，管理员无需记录），以及引用校园问题的示例会话。验证：`mvnw.cmd test -Dtest=SeedDataScriptTest` 通过；种子脚本可在空库重复执行不产生重复行。
- [x] 1.4 默认数据源切换为 `campus`：更新 `application.yml`、`src/test/resources/application-test.yaml`、`docker-compose.yml`、`deploy/docker-compose.prod.yml`、`deploy/.env.example` 中的数据源 id、schema 名与说明。验证：启动后 `GET /api/text2sql/datasources` 的默认项为 `campus`，日志显示种子来源为校园脚本。
- [x] 1.5 注册默认角色改为 `student`，并更新 `AuthService` 相关注释与 `UserView` 文档注释。验证：`AuthControllerTest` 断言注册返回 `role=student`。

## 2. 权限内核

- [x] 2.1 扩展 `security/Roles`：新增 `student` / `counselor` 常量，`normalize` 对未知角色返回 `null`（不再降级为 `user`），`isAdmin` 语义不变；更新 `SecurityConfig` 的角色映射与相关注释。验证：新增 `RolesTest` 覆盖三种角色 + 未知角色，`mvnw.cmd test -Dtest=RolesTest` 通过。
- [x] 2.2 新增 `user_scope` / `datasource_access` 的 R2DBC 仓储与 `DataScope` 不可变模型（role、classIds、studentId、studentNo、allowedDatasources、是否有范围）。验证：仓储测试覆盖按用户读取与空结果。
- [x] 2.3 实现 `DataScopeResolver`：学生 → 本人；辅导员 → 所管班级集合；管理员 → 全量；非管理员无映射 → 明确拒绝异常。验证：`DataScopeResolverTest` 覆盖四类结果（含未配置范围），并与应用库集成测试通过。
- [x] 2.4 定义 `(role, table) → allow / predicate template` 策略表，覆盖 `class`、`course`、`student`、`score`、`attendance` 三角色矩阵；白名单外的表一律拒绝。验证：`TablePolicyTest` 逐格断言允许/拒绝。
- [x] 2.5 引入 SQL 解析依赖（JSqlParser）并实现 `DataScopeRewriter`：表引用替换为带谓词的派生表、保留别名、排除 CTE 名、重名别名后缀、解析失败与不支持结构即抛错。验证：`DataScopeRewriterTest` 覆盖 SELECT、JOIN、UNION、嵌套子查询、CTE、自连接、无别名、解析失败共 8 个用例。
- [x] 2.6 集成验证行级过滤真实生效：在 H2 校园数据上执行"学生查全部成绩/查他人学号/辅导员跨班/管理员全量"。验证：新增 `DataPermissionIntegrationTest`，四种场景的结果行与分析断言一致。

## 3. 接线两条路径

- [x] 3.1 `Text2SqlPipelineService` 签名由 `userId` 改为 `DataScope`，在 `SqlSafetyValidator` 与 `SqlExecutor` 之间调用重写器，并用重写后的 SQL 生成 `SqlEvent`/`Text2SqlResult`。验证：更新 `Text2SqlPipelineServiceTest`，断言输出的 SQL 已含过滤条件、`Text2SqlResult.sql()` 与实际执行一致。
- [x] 3.2 `FullSchemaProvider` / `RagSchemaProvider` 按 `DataScope` 裁剪 schema 渲染结果。验证：`RagSchemaProviderTest` 与新增断言确认学生 schema 不含白名单外表。
- [x] 3.3 `tool/SqlTools` 的 `query_database` / `list_tables` / `describe_table` 接收 `DataScope`（不加 `@ToolParam`，走上下文注入），元数据只返回白名单内对象。验证：`SqlToolsTest` 断言学生看不到白名单外表且查询结果受限。
- [x] 3.4 `Text2SqlAgentService` 解析 `DataScope` 并写入 `RuntimeContext`，替换 `SqlTools` 现有的 `null` 身份调用。验证：`Text2SqlAgentServiceTest` 断言工具调用携带调用者 scope；一次真实 agent 链路手工验证学生查询不越界。（已在线验证：agent 的 3 条 SQL 均带 student_no/student_id 过滤）
- [x] 3.5 `Text2SqlController` 的 `/stream`、`/query`、`/agent/stream` 从 JWT 解析 `DataScope` 并下传。验证：更新 `Text2SqlControllerTest` / `Text2SqlControllerPersistenceTest`，覆盖三种角色与未配置范围。

## 4. 数据源白名单与管理员

- [x] 4.1 在 pipeline 与 agent 工具执行前校验数据源：非管理员只能用被授权数据源；未指定时只在允许集合内回落；集合为空直接失败。验证：`DatasourceAccessTest` 覆盖已授权、未授权、无可用数据源三种情况。
- [x] 4.2 新增管理员 API：读取「数据源 × 角色」矩阵、新增/移除授权，仅 `admin` 可调用。验证：`DatasourceAccessControllerTest` 断言管理员 200、学生 403、矩阵内容正确。
- [x] 4.3 授权变更即时生效（无重启）：修改后下一次查询按新授权判定。验证：集成测试中先断言拒绝、插入授权、再断言成功。
- [x] 4.4 管理员页面：新增 `views/DataSourceAccessView.vue` 与 `api/admin.ts`，路由 `meta.admin` 守卫、导航按角色显示；非管理员直接访问跳回对话页。验证：`npm run build` 通过；管理员手工勾选/撤销后矩阵与查询行为同步。（已用无头浏览器验证：页面渲染 campus 行 + 学生/辅导员两个授权勾选；API 撤销后 403、恢复后 200）

## 5. Agent 执行过程展示

- [x] 5.1 定义 `ExecutionListener` 并在 pipeline 重写/执行后回调（datasourceId、实际 SQL、行数、失败原因），随 `DataScope` 注入 `RuntimeContext`。验证：`Text2SqlPipelineServiceTest` 断言监听器收到与执行一致的信息。
- [x] 5.2 `Text2SqlAgentService` 将 listener 事件与 AgentScope 事件流合并：`ToolCallStartEvent`/`ToolCallEndEvent`/`ToolResultEndEvent` 映射为 `step`，listener 映射为 `sql`（含数据源与"已应用范围"标记），失败映射为 `error` 且不清除已发事件。验证：`AgentSideChannelTest`（假事件流 + 假 listener）覆盖成功、失败、无查询三种路径。
- [x] 5.3 前端 `ChatView` 复用 step/sql 渲染：agent 模式展示步骤、SQL 代码块与数据范围提示；未调用查询工具时不渲染空 SQL 区块。验证：`npm run build` 通过；手工对话确认顺序与内容。（已用无头浏览器验证：Agent 模式展示工具步骤、SQL 代码块、"已按当前数据范围过滤"标记与行数）
- [x] 5.4 端到端确认学生 agent 会话中展示的 SQL 已含本人过滤条件，且步骤顺序与实际调用一致。验证：手工验收记录（问题、角色、展示 SQL、结果行数）。（在线记录：agent 3 次查询均含 student_no/student_id 过滤，1 行结果）

## 6. 文档与整体验收

- [x] 6.1 更新 `README.md`、`docs/nl2sql.md`、`docs/deploy.md`：校园域五表、演示账号表（含角色与范围）、行级权限模型、管理员白名单操作、数据卷重建步骤。验证：按文档从零启动一次，命令与账号可直接照抄执行。
- [x] 6.2 全量回归：`mvnw.cmd test`（后端）与 `npm run build`（前端）全部通过，且不存在以电商 schema 或 `admin/user` 旧角色为前提的残留断言。
- [x] 6.3 角色 × 问题验收矩阵人工过一遍：学生本人、学生越界、学生问全校、辅导员跨班、管理员全量、未授权数据源、未配置范围账号。验证：逐条记录实际结果与预期一致。（已在线逐条执行并通过，见部署验证记录）

## Workflow follow-up

- 在项目评审通过后运行 `/opsx-archive` 归档本 change。
- 归档后确认 `openspec/specs/` 下生成四个能力主 spec，且 `data-permission` 的 Purpose 已由 delta 正确带入。
