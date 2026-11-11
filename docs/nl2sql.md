# NL2SQL 架构说明（DB-GPT 精简实现）

本文说明 `neko-text2sql-backend` 如何把"自然语言 → SQL → 执行 → 结果"跑通，以及它与
[DB-GPT](https://github.com/eosphoros-ai/DB-GPT) 的对应关系和后续扩展点。

## 1. 设计取向

采用 **"确定性流水线为主、Agent 编排为辅"** 的分层：

- **确定性流水线（产品主路径）**：一次模型调用 + 一次查询，延迟与失败模式可控，结果结构化。
- **Agent（可选）**：AgentScope `HarnessAgent` 通过 `@Tool` 调用同一条流水线，负责会话、追问与解释。

这样把两个最难的问题——(a) NL2SQL 正确性、(b) 多步智能体编排——解耦，避免同时调试。

## 2. 请求流程（`/api/text2sql/stream`）

> **分层与数据访问**：代码遵循 **控制层（`controller`）→ 服务层（`service`）→ 数据访问层（`repository`）** 三层架构，
> Controller 只依赖 Service，Service 依赖 Repository。**应用数据库**（用户/会话/模型配置）通过
> **Spring Data R2DBC**（`DatabaseClient`）**非阻塞**访问；**目标数据源层**（内省 + 只读执行）仍用 JDBC（在
> `boundedElastic` 上运行）。MySQL 的库表由 docker-compose 初始化脚本创建（挂载 `db/app-schema.sql`）。

```
question
   │
   ▼
DataSourceRegistry.resolve(datasourceId)         # 取连接池（缓存）
   │
   ▼
SchemaProvider.renderForPrompt()                 # Phase 1: 全量 schema DDL（含 FK 关系）
   │  └─ SchemaIntrospector: JDBC DatabaseMetaData → TableSchema/ColumnSchema/ForeignKeySchema
   ▼
PromptAssembler → SqlGenerationService           # AgentScope Model（dashscope:qwen-plus）
   │  └─ 期望 JSON: {"sql": "...", "explanation": "..."}
   ▼
SqlResultParser（宽松解析：JSON → ```sql 代码块 → 原文）
   │
   ▼
SqlSafetyValidator（单语句 + SELECT/WITH-only + 关键字黑名单）
   │
   ▼
SqlExecutor（只读连接 + maxRows + timeout，boundedElastic）
   │
   ▼
SSE: step(introspect/generate/execute) → sql → rows → done
```

## 3. 关键组件

| 组件 | 包 | 职责 |
|------|----|------|
| `DataSourceRegistry` | `datasource` | 按配置构建/缓存 Hikari 连接池；启动时按需执行 `db/*.sql` 种子 |
| `SchemaIntrospector` | `schema` | JDBC 元数据 → 表/列/主键/外键模型 |
| `SchemaProvider` / `FullSchemaProvider` | `schema` | 生成提示词用 schema 文本（带字符预算） |
| `SchemaRenderer` | `schema` | 表结构 → 紧凑 DDL |
| `SqlSafetyValidator` | `sql` | 只读校验（多语句、非 SELECT、危险关键字） |
| `AccessContextResolver` / `EffectiveAccess` | `access` | 每次请求从应用库解析账号状态、令牌版本、有效角色/权限、数据源授权、数据范围与主体属性 |
| `DataScopePolicyEngine` | `access` | 合并同名表的角色策略：`DENY` > `ALL` > `FILTER`（多个 `FILTER` 取 OR），未配置即拒绝 |
| `DataScopeRewriter` | `sql` | AST 重写：按策略把受保护表替换为带过滤谓词的派生表（fail-closed，CTE 安全） |
| `DatasourceAccessGuard` | `datasource` | 数据源白名单判定与默认回落 |
| `SqlExecutor` | `sql` | 只读执行、行数上限、超时、结果集映射 |
| `PromptAssembler` | `nl2sql` | 系统/用户提示词 |
| `SqlGenerationService` | `nl2sql` | 调用 AgentScope `Model` 生成 SQL |
| `SqlResultParser` | `nl2sql` | 从模型输出中宽松提取 `{sql, explanation}` |
| `Text2SqlPipelineService` | `service` | 编排上述步骤，产出 `Flux<Text2SqlEvent>` |
| `DataScopeDescriber` | `security` | 范围文案的唯一来源：`summary`（兜底澄清用短文案）、`promptBlock`（注入生成/Agent 提示词）、`restricted`（fail-closed） |
| `ScopeAnswerGuard` | `security` | 受限账号的否定断言兜底：在 SSE 流末尾追加一次范围澄清 |
| `SqlTools` | `tool` | `@Tool`：`query_database` / `list_tables` / `describe_table`；`query_database` 返回 `{result, dataScope}` |
| `Text2SqlController` | `web` | REST + SSE，映射事件为 `step/sql/rows/done/error` |

## 4. 安全模型

多层防御，任一层不单独承担安全：

1. **语句级**：仅单条语句；必须以 `SELECT` / `WITH` 开头；标识符扫描阻断
   `INSERT/UPDATE/DELETE/DROP/ALTER/.../INTO/...`。
2. **权限级（强制的数据边界）**：生成 SQL 后先由 `DataScopePolicyEngine` 按“角色 → 数据源 →
   表”解析策略，再由 `DataScopeRewriter` 做 AST 重写。策略只有三种，均由管理端配置、存表：
   - `DENY`：该表一律拒绝（优先级最高，压过同表的 `ALL`/`FILTER`）；
   - `ALL`：不附加行过滤（优先于 `FILTER`）；
   - `FILTER`：把 `column IN/= 主体属性` 注入为派生表谓词；同一张表有多条 `FILTER` 时取 OR。
   未配置任何策略的表默认拒绝。学生、辅导员的具体范围来自种子数据（本人 / 所管班级），
   不再是代码常量。若模型写了越界条件，只会得到空集；解析失败、结构不支持、引用白名单外的表、
   递归 CTE 或同名 CTE 遮蔽一律拒绝执行。`access.bypass_data_scope`（内置管理员）跳过行过滤，
   但仍受表可见性与 SQL 只读校验约束。
3. **数据源级**：非管理员只能使用**有效角色**（含继承）被授权的数据源；授权集合为空时失败，
   不回落到全局默认。角色→数据源矩阵存于 `access_role_datasource`，旧的 `datasource_access`
   仅作兼容与迁移回填。
4. **账号与令牌级**：每个受保护请求都校验 `app_user.enabled` 与 JWT `ver` 是否等于
   `app_user.token_version`；停用、启用、重置密码都会递增版本，旧令牌在下一次请求即失效。
   JWT 的 `role` claim 仅用于展示，授权只读取数据库中的有效角色/权限。
5. **管理接口级**：角色/权限/数据范围/数据源矩阵需要 `access.manage`；用户列表/详情需要
   `user.read`，用户写操作需要 `user.manage`；审计读取需要 `audit.read`。防提权规则禁止管理员
   授予或继承自己未持有的权限，并保护最后一个 `access.manage` 持有者与内置 `admin` 角色。
6. **连接级**：`Connection.setReadOnly(true)`；建议数据源使用只读账号。
7. **资源级**：`Statement.setMaxRows(rowLimit+1)`（检测截断）、`setQueryTimeout(timeout)`。
8. **线程级**：阻塞 JDBC 统一在 `blockingScheduler`（每任务一个虚拟线程）上执行，绝不阻塞
   Netty 事件循环。

### 4.1 可配置访问控制（角色/权限/数据范围存表）

角色、权限与数据范围都是数据，不是代码常量；新增角色或调整范围不需要改代码、也不需要重新登录：

- **表**：`access_role`（编码不可变、单父继承、内置标记、启用状态）、`access_permission`
  （权限目录）、`access_role_permission`、`access_user_role`、`access_role_datasource`、
  `access_data_scope`、`access_audit`。无数据库外键，引用在服务层校验；角色/用户停用而非删除。
- **权限键**：`chat.use`、`datasource.read`、`datasource.write`、`model.manage`、`access.manage`、
  `user.read`、`user.manage`、`audit.read`、`access.bypass_data_scope`。
- **合并规则**：同一张表（同一角色、同一数据源）上 `DENY` > `ALL` > `FILTER`；多个 `FILTER`
  之间为 OR；没有任何策略即拒绝。继承来的角色与其父角色一并参与合并。
- **管理 API**：`/api/admin/access-control/**`。角色/权限/数据范围/数据源矩阵 → `access.manage`；
  用户列表/详情 → `user.read`，用户写操作 → `user.manage`；审计读取 → `audit.read`。
- **审计**：每次变更写 `access_audit`（操作者、动作、对象、before/after、结果），密码材料永不入审计；
  被拒绝的提权/锁定操作也会写 `DENIED`。
- **生效时机**：权限与数据范围在每次请求重新解析，改角色后下一次请求立即生效；停用账号、重置密码
  会递增 `token_version`，旧令牌立即失效。

## 5. SSE 事件契约（前端契约，与 AgentScope 内部事件解耦）

| 事件 | 负载 |
|------|------|
| `step` | 流水线 `{phase, detail}`；Agent `{phase:"tool", status, tool, state?, reason?}` |
| `sql` | 流水线 `{datasourceId, dialect, sql, explanation}`；Agent `{datasourceId, sql, rowCount, scopeApplied}` |
| `rows` | `{columns:[{name,typeName}], rows:[{col:val}], rowCount, truncated, elapsedMs}` |
| `chart` | `{type, xField, yField, title}` |
| `delta` | 文本（Agent 模式）；受限账号的否定断言会在 `done` 前追加**一条**范围澄清 `delta` |
| `done` / `error` | `[DONE]` / 文本或 `{message}` |

两种路径展示的 `sql` 都是**已应用权限后实际执行**的语句。

Agent 流的追加澄清不改变事件契约：仍然是 `delta`，仍然只追加、不重写已下发的文本，
`done` 始终是最后一个事件，前端无需改动。

新增事件名对既有 `EventSource` 监听器无影响，因此契约可向后兼容地扩展。

## 6. Agent 模式

`/api/text2sql/agent/stream` 使用 AgentScope `HarnessAgent`：

- 通过 `HarnessAgentFactory` 注册 `SqlTools` 到 `Toolkit`，并 `disableShellTool()` 收敛攻击面。
- 系统提示词要求：**任何数据问题都调用 `query_database`**（高层单工具，内部即流水线），
  避免"list → describe → run"式的多跳 ReAct 造成的高延迟与不稳定。
- 记忆/会话由 AgentScope 的 `AgentStateStore` 承担（默认 `JsonFileAgentStateStore`，
  多副本可换 Redis/JDBC）。

### 6.1 范围感知回答

权限过滤是执行层的硬约束，但模型如果不知道调用者的范围，就会把"被过滤掉"说成"数据不存在"。
因此 Agent 路径在三个位置显式带上范围：

1. **提示词**：`HarnessAgentFactory.create(Model, DataScope)` 按请求用
   `PromptCatalog.render` 渲染 `prompts/agent-system.md` 的 `{{data_scope}}`
   （文案来自 `DataScopeDescriber.promptBlock`，与流水线生成阶段**逐字相同**）。
   没有"无范围"的重载，调用方无法漏传。`agent-system.md` 同时给出三条回答规则：
   - 禁止把权限过滤表述为"数据库中没有记录/不存在"；
   - 禁止为判断范围外数据是否存在而发起探测查询，也禁止对范围外对象下存在性结论；
   - 管理员（`restricted=false`）按实际结果作答，不追加范围免责声明。
2. **工具返回**：`query_database` 返回
   `{"result": {sql, result, chart, ...}, "dataScope": {restricted, role, summary}}`。
   `result` 与既有 `Text2SqlResult` 结构一致（未改字段），`dataScope` 是本次结果的范围依据；
   `sql_runner` 子代理同样从 `result` 读取 SQL 与行。
3. **确定性兜底**：`ScopeAnswerGuard` 累积 `delta` 文本，若受限账号的回答出现否定断言
   （`不存在` / `没有数据` / `数据库中只有` / `未找到` / `没有找到` / `无数据`）且**尚未**说明范围
   （`数据范围` / `权限范围` / `仅本人` / `所管班级` / `不可见` / `无权查看` / `仅限` / `可查看范围`），
   则在 `done` 之前追加一条澄清：
   `（说明：当前账号的数据范围仅限{summary}；未返回的数据可能在该范围之外，不能据此判断数据库中不存在。）`
   管理员永远不会触发；已说明范围的回答也不会重复追加。守卫只累积文本、不缓冲整个流，
   因此增量下发与首字延迟不受影响。

范围文案只包含调用者**自己**的标识（本人学号、可管理班级 id），从不渲染范围外的班级、学号或表名。

## 7. 与 DB-GPT 能力对照

| DB-GPT 能力 | 状态 | 本工程实现 / 计划 |
|-------------|------|-------------------|
| 数据源接入 + 连接池缓存 | ✅ | `DataSourceRegistry`（Hikari，按 id 寻址） |
| Schema 抽取 | ✅ | `SchemaIntrospector`（实时，唯一事实来源） |
| NL2SQL 流水线 | ✅ | `Text2SqlPipelineService` |
| SQL 只读执行 | ✅ | `SqlExecutor` + `SqlSafetyValidator` |
| Agent + 工具调用 | ✅ | AgentScope `HarnessAgent` + `SqlTools` |
| Schema RAG（向量检索 top-k） | ✅ | `schema.RagSchemaProvider`（AgentScope `EmbeddingModel` + 余弦检索 + 外键邻居扩展；`text2sql.rag.enabled=true` 启用） |
| 数据源管理 CRUD / 连接测试 | ✅ | `web.DataSourceController`（`/api/text2sql/datasources`） |
| 知识库 RAG（MS-RAG 简化版） | ✅ | `knowledge.KnowledgeService` + `web.KnowledgeController`（有 embedding 走语义，否则词法回退） |
| 多智能体（planner/executor/reporter） | ✅ | `config.HarnessAgentFactory#subagentDeclarations`（schema_explorer / sql_runner / report_writer） |
| GBI 图表 | ✅ | `gbi.ChartInference` → SSE `chart` 事件 → 前端 ECharts |
| AWEL 工作流 DAG | ⏳ | 以流水线 `step` 事件 + 子代理编排替代；可视化 DAG 编排未做 |
| 用户 / 登录（Spring Security + JWT） | ✅ | `config.SecurityConfig` + `security.JwtService` + `web.AuthController`（应用库 MySQL） |
| 每用户模型配置 | ✅ | `modelconfig.ModelConfigRepository` / `ModelFactory` / `ModelResolver` + `web.ModelConfigController` |
| 会话管理（左侧历史树） | ✅ | `conversation.ConversationService` + `web.ConversationController`（消息含 sql/rows/chart） |

## 8. 扩展点

- **Schema RAG**：实现 `RagSchemaProvider implements SchemaProvider`（表/列摘要向量化 → top-k →
  展开为 DDL，并**始终带出命中表的外键邻居**），替换 bean 即可，流水线无需改动。
- **多数据源**：在 `application.yaml` 增加 `items[]` 条目即可；前端从 `/datasources` 选择。
- **新的模型**：设置 `TEXT2SQL_MODEL`（如 `openai:gpt-4.1`）与对应 Key 即可切换。

## 9. 扩展能力（RAG / 知识库 / GBI / 多智能体）

- **Schema RAG**（`text2sql.rag.enabled=true`）：`RagSchemaProvider` 用 AgentScope `EmbeddingModel`
  （DashScope `text-embedding-v3`）对表摘要向量化并缓存，按问题取 top-k，再**扩展外键邻居**（连接可达性），
  最后在字符预算内渲染 DDL。任何异常或全部低于阈值时**回退全量 schema**，绝不因检索失败阻断 NL2SQL。
- **三类持久化知识库**（`text2sql.knowledge.enabled=true`）：
  - **元数据**（按数据源）由 `MetadataSyncService` 内省后每表一条，`item_key = METADATA:{ds}:{table}` 幂等 upsert；
  - **业务知识**（数据源或全局）与**智能体知识**（按 Agent）支持文本条目、文档上传与网页导入；
  - 向量与分块存 PostgreSQL + `pgvector`（`knowledge_chunk`，HNSW 余弦索引），原文/解析产物存 MinIO 私有桶；
  - 入库在事务提交后异步执行，状态机 `PENDING → PARSING → EMBEDDING → READY/FAILED`，失败保留阶段，
    重试从最便宜的阶段继续（`EMBED` 失败不会重跑 MinerU）；
  - 解析：Tika 处理 Office/文本/HTML/URL；MinerU 精准 API 处理 PDF/图片，产出页码与 0–1000 归一化 bbox；
  - 分块**页内合并、不跨页**，因此每个 chunk 只有一个 `page_no`、bbox 并集、字符区间与 quote；
  - 检索链：术语改写 → 业务/智能体双路召回 → 表级召回与列跟随 → 外键补一跳（再次白名单过滤）→
    可选 LLM 精选 → 预算内渲染 DDL；任一路失败不阻断其他路，最终失败回退全量 schema 并记录原因；
  - `KnowledgeSchemaProvider`（`@Primary`）同时实现 `SchemaProvider` 与 `CitationAwareSchemaProvider`，
    流水线通过 `ObjectProvider` 可选注入后者，以 SSE `citations` 事件下发引用；引用随助手消息持久化
    （`message.citations_json`），刷新会话后仍可打开原文。
  - 前端 `/knowledge` 管理页 + `CitationDrawer`：PDF 用 `pdfjs-dist`（动态加载）渲染并按
    `bbox/1000 × viewport` 叠加高亮框；图片按百分比定位；Office/网页用文本快照 + `<mark>`，偏移失配时回退 `quote` 匹配。
- **旧内存知识库**：`KnowledgeService`（`/api/text2sql/knowledge/legacy/*`）保留为兼容别名。
- **多智能体**：`HarnessAgentFactory#subagentDeclarations` 声明 `schema_explorer` / `sql_runner` /
  `report_writer` 三个子代理（`text2sql.agent.subagents-enabled`），父代理经 AgentScope 子代理机制委派
  （planner/executor/reporter 分工）。
- **GBI 图表**：`gbi.ChartInference` 依据结果列类型与时间列推断 `BAR/LINE/PIE/NONE`，以 SSE `chart` 事件下发；
  前端用 ECharts 出图。

## 10. 测试

`mvn test` 覆盖（**无需模型 / embedding Key**）：

- `SqlSafetyValidatorTest` — 只读校验（13 例）。
- `Nl2SqlCoreIntegrationTest` — 真实 H2：内省、DDL 渲染、只读执行、行数上限。
- `Text2SqlPipelineServiceTest` — 桩模型驱动的全流水线（含阻断 / 执行失败 / 事件顺序含 chart）。
- `Text2SqlControllerTest` — Web 层 `/health` 与 `/datasources`。
- `DataSourceManagementIntegrationTest` — 数据源注册/替换/删除/连接测试。
- `RagSchemaProviderTest` — RAG 选表 + 外键邻居扩展 + 失败回退（内置确定性伪 embedding）。
- `KnowledgeServiceTest` — 旧内存知识库入库/检索/删除（词法回退路径）。
- `KnowledgePropertiesBindingTest` — `text2sql.knowledge.*` 绑定（含 `MINERU_TOKEN`）与默认关闭。
- `ChunkingServiceTest` — 页内合并/不跨页、bbox 并集、字符区间可切回原文、缺坐标报错。
- `KnowledgeSearchServiceTest` — 双路召回、引用装配、表级白名单裁剪、全量 schema 回退。
- `TikaDocumentParserTest` / `MinerUDocumentParserTest` — 文本切块字符区间、MinerU fixture 的
  `page_idx+1`/bbox 映射、`full.md` 与 `content.json` 产物。
- `DocumentParserSelectorTest` / `HttpMinerUClientTest` — 引擎选择与 MinerU 未配置 Token 的可读错误。
- `ChartInferenceTest` — GBI 图表推断。
- `HarnessAgentFactorySubagentsTest` — 多智能体子代理声明。
- `AuthControllerTest` — 注册/登录/当前用户 + 受保护接口 401（真实 JWT）。
- `ConversationControllerTest` — 会话 CRUD 与用户隔离。
- `ModelConfigControllerTest` — 模型配置 CRUD（API Key 不外泄）。
- `AccessControlRoleTest` / `AccessControlPermissionTest` / `AccessControlDataScopeTest` /
  `AccessControlUserTest` / `AccessControlDatasourceMatrixTest` / `AccessControlAuditTest` /
  `AccessControlEscalationTest` — 角色/权限矩阵/数据范围/用户生命周期/数据源矩阵/审计/防提权与
  lockout 的真实 HTTP + H2 用例（含审计行、下一次请求生效、`user.read`/`user.manage`/`audit.read`
  路由隔离）。
- `DataScopeRewriterTest` / `DataScopePolicyEngineTest` — `DENY` > `ALL` > `FILTER` 合并、CTE
  同名遮蔽/递归 CTE/schema 限定引用、越界条件与解析失败 fail-closed。
- `AccessStatusIntegrationTest` — 停用账号与过期 `token_version` 在下一次请求被拒绝。
- `DataScopeDescriberTest` — 学生/辅导员/管理员三种范围文案，且受限文案不含范围外班级、学号或表名。
- `ScopeAnswerGuardTest` — 触发、不触发、已含范围说明、管理员四种情况。
- `HarnessAgentFactoryTest` / `HarnessAgentFactorySubagentsTest` — 按请求渲染 `{{data_scope}}`（学生/管理员）、
  无残留占位符、`sql_runner` 提示说明 `{result, dataScope}` 信封。
- `Text2SqlControllerAgentStreamTest` — 澄清 `delta` 只追加一次且 `done` 仍是最后一个事件。

> 所有测试运行在 `test` profile（H2 应用库），无需 MySQL。测试通过真实 `/api/auth/register`+`/login` 取 JWT 后再访问受保护接口。

> 需要 Key 才能实机验证的部分：真实 SQL 生成（`SqlGenerationService`）、embedding 语义检索（`text2sql.rag.enabled=true`）、Agent 与子代理的实际编排。

## 11. 线程模型（阻塞工作与虚拟线程）

- **事件循环不跑阻塞代码**：控制器与流水线只做编排，任何 JDBC / MinIO / 解析调用都在调度器上执行。
- **应用库**（用户、会话、消息）走 **R2DBC**，全链路非阻塞。
- **目标数据源**（任意 JDBC 数据源的内省与查询）、**知识库 JDBC**、**MinIO SDK**、**Tika/MinerU 解析**天然是阻塞的：
  - 请求路径上的这类工作统一调度到 `blockingScheduler`（`config/BlockingSchedulerConfig`）——
    **每个任务一个虚拟线程**（Java 25），不再占用 `boundedElastic`（池大小上限为 10×CPU）。
  - 入库流水线使用独立的 `knowledgeExecutor`（有界线程池），避免大文件解析挤占请求路径。
- **为什么 pgvector 仍是 JDBC**：`org.postgresql:r2dbc-postgresql` 自 1.0.3 起原生支持 `vector`
  （`Vector.of(float...)`、`row.get("embedding", Vector.class)`，Codec 通过 SPI 自动注册），技术上可迁。
  但同一条检索链还要做目标库 JDBC 内省和 MinIO 预签名，二者无法响应式化，因此单迁 pgvector
  只会多维护一套双栈、并不能让链路变成纯响应式。保留 JDBC + 虚拟线程是当前性价比最高的选择；
  若以后出现实测瓶颈，再单独迁移向量检索（接口已隔离在 `VectorStore`）。
- **测试**：`BlockingSchedulerConfigTest` 断言阻塞任务确实跑在虚拟线程上。
