# NL2SQL 架构说明（DB-GPT 精简实现）

本文说明 `text2sql-backend` 如何把"自然语言 → SQL → 执行 → 结果"跑通，以及它与
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
| `TablePolicy` | `security` | 角色 → 可访问表 + 行过滤谓词模板 |
| `DataScopeResolver` / `DataScope` | `security` | 从应用库解析调用者的角色、班级、学号与可用数据源 |
| `DataScopeRewriter` | `sql` | AST 重写：把受保护表的引用替换为带过滤谓词的派生表（fail-closed） |
| `DatasourceAccessGuard` | `datasource` | 数据源白名单判定与默认回落 |
| `SqlExecutor` | `sql` | 只读执行、行数上限、超时、结果集映射 |
| `PromptAssembler` | `nl2sql` | 系统/用户提示词 |
| `SqlGenerationService` | `nl2sql` | 调用 AgentScope `Model` 生成 SQL |
| `SqlResultParser` | `nl2sql` | 从模型输出中宽松提取 `{sql, explanation}` |
| `Text2SqlPipelineService` | `service` | 编排上述步骤，产出 `Flux<Text2SqlEvent>` |
| `SqlTools` | `tool` | `@Tool`：`query_database` / `list_tables` / `describe_table` |
| `Text2SqlController` | `web` | REST + SSE，映射事件为 `step/sql/rows/done/error` |

## 4. 安全模型

多层防御，任一层不单独承担安全：

1. **语句级**：仅单条语句；必须以 `SELECT` / `WITH` 开头；标识符扫描阻断
   `INSERT/UPDATE/DELETE/DROP/ALTER/.../INTO/...`。
2. **权限级（强制的数据边界）**：生成的 SQL 在执行前经 `DataScopeRewriter` 做 AST 重写，
   每张受保护表的引用变成带过滤谓词的派生表（学生按本人、辅导员按所管班级）；模型未写过滤条件
   也会被补上，写了越界条件只会得到空集。解析失败、结构不支持或引用白名单外的表时直接拒绝执行。
3. **数据源级**：非管理员只能使用角色被授权的数据源；授权集合为空时失败，不回落到全局默认。
4. **连接级**：`Connection.setReadOnly(true)`；建议数据源使用只读账号。
5. **资源级**：`Statement.setMaxRows(rowLimit+1)`（检测截断）、`setQueryTimeout(timeout)`。
6. **线程级**：阻塞 JDBC 全部在 `Schedulers.boundedElastic()` 上执行，绝不阻塞 Netty 事件循环。

## 5. SSE 事件契约（前端契约，与 AgentScope 内部事件解耦）

| 事件 | 负载 |
|------|------|
| `step` | 流水线 `{phase, detail}`；Agent `{phase:"tool", status, tool, state?, reason?}` |
| `sql` | 流水线 `{datasourceId, dialect, sql, explanation}`；Agent `{datasourceId, sql, rowCount, scopeApplied}` |
| `rows` | `{columns:[{name,typeName}], rows:[{col:val}], rowCount, truncated, elapsedMs}` |
| `chart` | `{type, xField, yField, title}` |
| `delta` | 文本（Agent 模式） |
| `done` / `error` | `[DONE]` / 文本或 `{message}` |

两种路径展示的 `sql` 都是**已应用权限后实际执行**的语句。

新增事件名对既有 `EventSource` 监听器无影响，因此契约可向后兼容地扩展。

## 6. Agent 模式

`/api/text2sql/agent/stream` 使用 AgentScope `HarnessAgent`：

- 通过 `HarnessAgentFactory` 注册 `SqlTools` 到 `Toolkit`，并 `disableShellTool()` 收敛攻击面。
- 系统提示词要求：**任何数据问题都调用 `query_database`**（高层单工具，内部即流水线），
  避免"list → describe → run"式的多跳 ReAct 造成的高延迟与不稳定。
- 记忆/会话由 AgentScope 的 `AgentStateStore` 承担（默认 `JsonFileAgentStateStore`，
  多副本可换 Redis/JDBC）。

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

> 所有测试运行在 `test` profile（H2 应用库），无需 MySQL。测试通过真实 `/api/auth/register`+`/login` 取 JWT 后再访问受保护接口。

> 需要 Key 才能实机验证的部分：真实 SQL 生成（`SqlGenerationService`）、embedding 语义检索（`text2sql.rag.enabled=true`）、Agent 与子代理的实际编排。
