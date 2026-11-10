# Text2SQL Monorepo

将自然语言问题转换为 SQL 并**真正执行**的全栈工程（DB-GPT 的 Java/Vue 精简实现）。
仓库根目录仅作为**容器**（不做 Maven 聚合），前端与后端是两个相互独立的子工程。

核心能力（对齐 DB-GPT 的 NL2SQL 主链路）：

1. **数据源接入** — 可插拔多数据源（默认 MySQL `campus`，测试用 H2 内存库），连接池缓存。
2. **Schema 抽取** — 通过 JDBC `DatabaseMetaData` **实时**内省表/列/主键/外键，作为唯一事实来源。
3. **NL2SQL 生成** — 复用 AgentScope 的模型层，把 schema + 问题转换为结构化 SQL（JSON）。
4. **安全执行** — 只读连接 + 单语句/SELECT-only 校验 + 行数上限 + 超时。
5. **结构化回传** — SSE 事件（`step` / `sql` / `rows`）驱动前端渲染 SQL 与结果表格。
6. **Agent 模式** — AgentScope `HarnessAgent` + `@Tool`，把上述能力作为工具交给智能体调用。
7. **可配置访问控制** — 角色、权限、数据范围、用户生命周期全部存表；管理端「权限管理」页六个 Tab，
   角色/账号变更下一次请求即生效，内置管理员与最后一个 `access.manage` 持有者受保护。

## 目录结构

```
text2sql-demo/                  # 仓库根（保持不变）
├── text2sql-frontend/          # 前端：Vue 3 + Vite + TypeScript + Element Plus
│   ├── src/api/                #   axios 封装 + SSE 流式客户端（step/sql/rows）
│   ├── src/stores/             #   Pinia 会话状态 + 数据源
│   ├── src/views/              #   ChatView / AboutView
│   └── vite.config.ts          #   /api 代理到后端 :8080
└── text2sql-backend/           # 后端：Java 25 + Spring Boot 4 (WebFlux) + AgentScope 2.0
    ├── pom.xml
    ├── agentscope/workspace/   #   AgentScope 工作区：AGENTS.md
    └── src/main/
        ├── resources/db/       #   演示数据脚本 + 应用库 schema（app-schema.sql）
        ├── resources/prompts/  #   提示词目录（集中管理，{{占位符}} 模板；见该目录 README）
        └── java/com/nekocong/text2sql/
            ├── controller/     # 控制层：Controller + dto（只依赖 Service）
            ├── service/        # 服务层：Auth/ModelConfig/DataSource/Conversation/Knowledge/Pipeline/Agent
            ├── repository/     # 数据访问层：Spring Data R2DBC（DatabaseClient，非阻塞）
            ├── prompt/         # 提示词加载（PromptCatalog，读取 resources/prompts/）
            ├── config/         #   配置：AgentProperties / SecurityProperties / RagConfig / ...
            ├── access/         #   可配置 RBAC：角色/权限/数据范围/用户/审计 + 解析与策略引擎
            ├── security/       #   JwtService + 账号状态/令牌版本校验
            ├── user|modelconfig|conversation|knowledge/  # 领域模型（记录）
            ├── datasource/     #   目标数据源：DataSourceRegistry（Hikari，JDBC）
            ├── schema|sql|nl2sql|gbi/  # 领域服务：内省 / 只读执行 / NL2SQL / 图表
            └── tool/           #   AgentScope @Tool（SqlTools）
```

## 技术栈

| 子工程 | 技术 |
|--------|------|
| text2sql-frontend | Vue 3、Vite、TypeScript、Pinia、Vue Router、Element Plus、Axios |
| text2sql-backend  | Java 25、Spring Boot 4.1、Spring WebFlux、**Spring Data R2DBC（应用库，非阻塞）**、Spring JDBC（目标数据源，只读执行）、AgentScope 2.0（HarnessAgent）、DashScope 模型 |

## 前置条件

- JDK 25、Maven 3.9+（或使用 `mvnw`）
- Node.js 20+ 与 npm
- **MySQL 8**（应用库：用户/会话/模型配置）。用 `docker compose up -d` 一键起库并自动建表，
  或用环境变量指向已有实例：`APP_DB_R2DBC_URL` / `APP_DB_USERNAME` / `APP_DB_PASSWORD`
- 模型 API Key：默认使用 DashScope，请设置环境变量 `DASHSCOPE_API_KEY`
  （缺少 Key 也能启动、也能跑测试与「数据源/Schema」相关接口，只是无法生成 SQL；
  也可登录后在「模型配置」里为每个用户单独配置模型与 Key）

## 启动后端

```bash
cd text2sql-backend
mvn spring-boot:run
```

接口（默认端口 `8080`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/text2sql/stream?message=...&datasourceId=...` | NL2SQL 流水线 SSE（`step` / `sql` / `rows` / `done` / `error`） |
| POST | `/api/text2sql/query` | 同步返回结构化结果（sql + 结果集） |
| GET  | `/api/text2sql/agent/stream?message=...&sessionId=...` | AgentScope 智能体 SSE（`delta` 令牌流 + `step` 工具步骤 + `sql` 实际执行语句） |
| GET  | `/api/text2sql/datasources` | 列出**当前账号可用**的数据源 |
| POST | `/api/text2sql/datasources` | 新增数据源（运行时，内存态） |
| PUT  | `/api/text2sql/datasources/{id}` | 更新数据源 |
| DELETE | `/api/text2sql/datasources/{id}` | 删除数据源 |
| POST | `/api/text2sql/datasources/{id}/test` | 测试已注册数据源连接 |
| POST | `/api/text2sql/datasources/test` | 测试候选数据源（不注册） |
| POST | `/api/text2sql/knowledge/documents` | 知识库文档入库/替换 |
| GET  | `/api/text2sql/knowledge/documents?namespace=` | 列出知识库文档 |
| DELETE | `/api/text2sql/knowledge/documents?namespace=&name=` | 删除知识库文档 |
| POST | `/api/text2sql/knowledge/search` | 知识库检索 |
| GET  | `/api/admin/datasource-access` | 角色 × 数据源授权矩阵（兼容旧页面） |
| PUT  | `/api/admin/datasource-access/{role}/{datasourceId}` | 授权（兼容旧页面） |
| DELETE | `/api/admin/datasource-access/{role}/{datasourceId}` | 撤销授权（兼容旧页面） |
| GET/POST | `/api/admin/access-control/roles` | 角色列表 / 新建（`access.manage`） |
| PUT | `/api/admin/access-control/roles/{id}` | 编辑角色（编码不可变、单父继承、内置保护） |
| GET | `/api/admin/access-control/permissions` | 权限目录 |
| GET/PUT | `/api/admin/access-control/roles/{id}/permissions` | 角色权限矩阵 |
| GET | `/api/admin/access-control/datasource-access` | 角色记录 × 数据源矩阵 |
| GET/PUT | `/api/admin/access-control/roles/{id}/datasources` | 角色数据源授权 |
| GET/PUT/DELETE | `/api/admin/access-control/data-scopes[/{id}]` | 数据范围策略（`ALL`/`FILTER`/`DENY`） |
| GET | `/api/admin/access-control/datasources/{id}/schema` | 只读表/列元数据（数据范围编辑器用） |
| GET/POST | `/api/admin/access-control/users` | 用户列表（分页，`user.read`）/ 创建（`user.manage`） |
| GET/PUT | `/api/admin/access-control/users/{id}` | 用户详情 / 修改显示名 |
| PUT | `/api/admin/access-control/users/{id}/status` | 停用/启用（旧令牌立即失效） |
| POST | `/api/admin/access-control/users/{id}/reset-password` | 重置密码（旧密码与旧令牌立即失效） |
| PUT | `/api/admin/access-control/users/{id}/roles` | 替换用户角色 |
| GET | `/api/admin/access-control/audit` | 审计日志（分页/过滤，`audit.read`） |
| POST | `/api/auth/register` | 注册（返回 JWT） |
| POST | `/api/auth/login` | 登录（返回 JWT） |
| GET  | `/api/auth/me` | 当前用户 |
| GET/POST | `/api/model-configs` | 每用户模型配置（列表/新增） |
| PUT/DELETE | `/api/model-configs/{id}` | 更新/删除模型配置 |
| POST | `/api/model-configs/{id}/test` | 模型连通性测试 |
| GET/POST | `/api/conversations` | 会话列表 / 新建 |
| PUT/DELETE | `/api/conversations/{id}` | 重命名 / 删除会话 |
| GET  | `/api/conversations/{id}/messages` | 会话消息历史 |
| GET  | `/api/text2sql/health` | 健康检查 |

> 除 `/api/auth/register`、`/api/auth/login`、`/api/text2sql/health` 与 `/actuator/**` 外，
> 所有接口都需要 `Authorization: Bearer <token>`。管理接口不再看 JWT 的 `role` claim，而是按权限：
> `/api/admin/**`（角色/权限/数据范围/数据源矩阵）需要 `access.manage`；用户列表/详情需要
> `user.read`，用户写操作需要 `user.manage`；审计读取需要 `audit.read`。`/api/auth/me` 会返回
> 当前账号的有效角色 `roles` 与权限 `permissions`，供前端导航/路由守卫使用（API 仍是安全边界）。
> SSE 端点（浏览器 `EventSource` 无法带请求头）支持 `?access_token=<token>` 查询参数。

### SSE 事件契约

| 事件 | 负载 | 说明 |
|------|------|------|
| `step` | 流水线 `{phase, detail}`；Agent `{phase:"tool", status, tool, state?, reason?}` | 阶段或工具步骤（Agent 失败步骤带可读原因） |
| `sql`  | 流水线 `{datasourceId, dialect, sql, explanation}`；Agent `{datasourceId, sql, rowCount, scopeApplied}` | **已应用数据权限后**实际执行的 SQL |
| `rows` | `{columns, rows, rowCount, truncated, elapsedMs}` | 结果集（行以列名为键的对象数组） |
| `chart`| `{type, xField, yField, title}` | GBI 图表推断（`BAR`/`LINE`/`PIE`/`NONE`） |
| `citations` | `Citation[]` | 本次回答使用的文档原文引用（页码/坐标/字符区间/预签名预览地址）；无文档引用时不发 |
| `delta`| 文本 | 仅 Agent 模式 |
| `done` | `[DONE]` | 流结束 |
| `error`| 文本或 `{message}` | 错误信息（含权限拒绝原因） |

## 演示账号与数据权限

演示库是**智慧校园**域（`class` / `course` / `student` / `score` / `attendance`），内置角色与默认可见范围：

| 账号 | 密码 | 内置角色 | 默认可见范围 |
|------|------|------|----------|
| `admin` | `admin123` | `admin` | 全部数据（`access.bypass_data_scope`），可进入「权限管理」页 |
| `student01` | `student123` | `student` | 仅本人（张伟，1 班） |
| `student02` | `student123` | `student` | 仅本人（李娜，1 班） |
| `student03` | `student123` | `student` | 仅本人（王强，2 班） |
| `counselor01` | `counselor123` | `counselor` | 1 班与 2 班全部数据 |

权限在服务端强制，与提示词无关：

- **角色是数据**：`access_role` / `access_permission` / `access_role_permission` /
  `access_user_role` 存表；可创建自定义角色、单父继承、启用/停用，新增角色不需要改代码。
  权限键：`chat.use`、`datasource.read`、`datasource.write`、`model.manage`、`access.manage`、
  `user.read`、`user.manage`、`audit.read`、`access.bypass_data_scope`。
- **行级**：`access_data_scope` 按“角色 → 数据源 → 表”配置 `ALL` / `FILTER` / `DENY`。
  合并规则 **`DENY` > `ALL` > `FILTER`**（多个 `FILTER` 取 OR），未配置即拒绝。生成的 SQL 在执行前
  被 AST 重写为带过滤谓词的派生表，无法通过提示注入、子查询、UNION 或同名 CTE 绕过；解析失败、
  递归 CTE、白名单外的表一律拒绝执行。非管理员只能引用被授权的数据源。
- **账号生命周期**：「权限管理 → 用户」可创建用户、修改显示名、停用/启用、重置密码、分配角色。
  用户名唯一、密码 BCrypt 哈希；接口与审计永不返回密码或哈希；不提供硬删除。停用、启用、重置密码
  会递增 `token_version`，旧令牌在下一次请求即被拒绝。
- **防提权与锁定**：管理员不能授予/继承/分配自己未持有的权限；最后一个 `access.manage` 持有者
  不能被停用、移除管理角色或通过角色权限/停用/改父级间接移除；内置 `admin` 角色不可停用、不可改父级。
  所有变更写入 `access_audit`（操作者、before/after、结果），被拒绝的提权也会记录 `DENIED`。
- 未配置数据范围的账号（例如刚注册的学生）一律拒绝，而不是返回空结果。

管理员界面：`/admin/access-control`（顶部导航「权限管理」，`access.manage` 可见），包含角色、权限、
数据范围、用户、数据源授权、审计六个 Tab。旧页面 `/admin/datasource-access` 保留兼容。

未提交 SQL 生成 Key 时，权限拒绝、数据源与 schema 相关接口仍可用；只有模型生成步骤需要 Key。

## 启动前端

```bash
cd text2sql-frontend
npm install
npm run dev        # http://localhost:5173
```

Vite 已将 `/api` 代理到 `http://localhost:8080`，前端无需配置后端地址。

## 配置

后端配置位于 `text2sql-backend/src/main/resources/application.yml`。

### 数据源 `text2sql.datasources.*`

| 键 | 说明 | 默认 |
|----|------|------|
| `default-id` | 默认数据源 id | `campus` |
| `items[].id` / `name` | 标识 / 展示名 | — |
| `items[].driver` / `url` / `username` / `password` | JDBC 连接参数 | — |
| `items[].dialect` | SQL 方言（省略则按 URL 推断） | 推断 |
| `items[].seed` | 启动时执行 `db/schema.sql` + `db/data.sql` | `false` |

主配置指向 MySQL 的 `campus` schema（`CAMPUS_DB_*`），并开启 `seed: true`，启动时自动建表并灌入校园种子数据。测试 profile 使用同名 H2 内存库。切换到其它库只需新增一个条目：

```yaml
text2sql:
  datasources:
    items:
      - id: mysql-campus
        name: Local MySQL
        driver: com.mysql.cj.jdbc.Driver
        url: "jdbc:mysql://localhost:3306/campus?useSSL=false&serverTimezone=UTC"
        username: root
        password: secret
        dialect: MYSQL
        seed: false
```

### 流水线限制 `text2sql.nl2sql.*`

| 键 | 说明 | 默认 |
|----|------|------|
| `row-limit` | 单次查询返回的最大行数 | `200` |
| `timeout-seconds` | 单语句查询超时（秒） | `15` |
| `schema-char-budget` | 注入提示词的 schema 字符预算 | `3000` |

### Schema RAG `text2sql.rag.*`（默认关闭）

| 键 | 说明 | 默认 |
|----|------|------|
| `enabled` | 启用 embedding 向量检索选表（否则用全量 schema） | `false` |
| `embedding-model` / `dimensions` | embedding 模型与维度 | `text-embedding-v3` / `1024` |
| `top-k` / `score-threshold` | 选表数量与相似度阈值 | `4` / `0.0` |

> 启用后需提供 `DASHSCOPE_API_KEY`（或 `text2sql.rag.api-key`）。异常时自动回退全量 schema。

### 知识库 `text2sql.knowledge.*`（默认关闭）

持久化三类知识库：**元数据**（按数据源，由内省同步生成）、**业务知识**（数据源或全局）、
**智能体知识**（按 Agent）。向量存 PostgreSQL + `pgvector`，原文与解析产物存 MinIO，解析产物与
引用位置一起落库，因此检索命中可回溯到原文具体位置。

启动依赖（`docker-compose.yml` 已包含，镜像默认走华为云 SWR）：

```bash
# Windows PowerShell：切回官方镜像源
$env:REGISTRY_PREFIX = ""
# bash
REGISTRY_PREFIX= docker compose up -d

docker compose up -d pgvector minio     # 5433 / 9000+9001
docker compose ps                       # 等健康检查通过
```

| 键 | 说明 | 默认 |
|----|------|------|
| `enabled` | 知识库总开关（`KNOWLEDGE_ENABLED`）；`false` 时回退全量 schema | `false` |
| `chunk-size` / `top-k` / `candidate-tables` | 分块长度、召回块数、候选表数 | `800` / `5` / `8` |
| `llm-select-enabled` / `llm-select-threshold` | 候选表超过阈值时由 LLM 精选，失败回退规则集 | `true` / `6` |
| `lexical-fallback` | 无 embedding 时允许确定性词法召回 | `true` |
| `pgvector.url` / `username` / `password` / `pool-size` | 向量库连接（`KNOWLEDGE_PGVECTOR_*`） | `jdbc:postgresql://localhost:5433/text2sql` |
| `minio.endpoint` / `access-key` / `secret-key` / `bucket` | 对象存储（`KNOWLEDGE_MINIO_*`），桶为私有 | `http://localhost:9000` / `text2sql-knowledge` |
| `minio.presign-ttl-seconds` | 原文预览预签名有效期（秒） | `600` |
| `parser.pdf-engine` / `image-engine` | PDF/图片解析引擎：`mineru`（带页码+坐标）或 `tika`（纯文本） | `mineru` |
| `parser.max-file-size-mb` / `max-pages` | 上传大小与页数上限 | `200` / `200` |
| `mineru.token` | MinerU Token（`MINERU_TOKEN`）；为空时 PDF/图片解析直接报可读错误 | 空 |
| `mineru.model-version` / `poll-timeout-seconds` / `max-concurrency` | 精准解析模型、轮询预算、并发上限 | `vlm` / `180` / `2` |

启用方式（三选一即可，MinerU 与 embedding 都是可选的）：

```bash
# 1) 只开持久化与词法检索（无需任何 API Key）
set KNOWLEDGE_ENABLED=true

# 2) 再加语义检索（复用 text2sql.rag.* 的 embedding 配置）
set TEXT2SQL_RAG_ENABLED=true
set DASHSCOPE_API_KEY=sk-...

# 3) 再加 PDF/图片版面解析
set MINERU_TOKEN=...
```

解析路径：docx/pptx/xlsx/txt/md/csv/html/URL 走 Tika；PDF/图片走 MinerU 精准 API
（`file-urls/batch` → PUT → 轮询 → `full_zip_url`），产出 `full.md`、规范化 `content.json`
与图片，`page_idx+1` 作为 1-based 页码，bbox 保持 0–1000 归一化。

管理员界面：`/knowledge`（顶部导航「知识库」，`access.manage` 权限守卫；`/api/text2sql/knowledge/**`
同样要求 `access.manage`，旧接口 `/api/text2sql/knowledge/legacy/**` 仍只要求登录）。页面提供三类分栏、
条目 CRUD、元数据同步、文档上传/网页导入、解析状态与失败重试、术语映射、检索调试，以及原文预览高亮。

管理接口（全部要求 `access.manage`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST/PUT/DELETE | `/api/text2sql/knowledge/items[/{id}]` | 条目列表/详情/新增/编辑/删除 |
| POST | `/api/text2sql/knowledge/items/{id}/retry` | 按失败阶段重试 |
| POST | `/api/text2sql/knowledge/metadata/sync?datasourceId=` | 同步数据源元数据 |
| GET/POST/DELETE | `/api/text2sql/knowledge/term-mappings[/{id}]` | 业务俗语 → 表/字段映射 |
| POST | `/api/text2sql/knowledge/documents` | multipart 上传（`file` + `knowledgeType` 等） |
| POST | `/api/text2sql/knowledge/documents/url` | 网页导入（保存快照） |
| GET | `/api/text2sql/knowledge/documents` | 文档列表与解析状态 |
| POST | `/api/text2sql/knowledge/documents/{id}/reparse?engine=` | 换引擎重新解析 |
| GET | `/api/text2sql/citations/{documentId}/file` | 同源下载原文（带 Bearer、含范围校验，前端预览用） |
| GET | `/api/text2sql/citations/{documentId}/preview-url` | 签发原文预签名地址（回答中引用可用，含范围校验） |
| GET | `/api/text2sql/citations/{documentId}/content` | 规范化文本快照（字符区间高亮用） |
| POST | `/api/text2sql/knowledge/search` `/search/debug` | 检索与检索调试（含 trace） |

> 旧的进程内知识库接口迁到 `/api/text2sql/knowledge/legacy/*`，行为不变。
> **回滚**：`KNOWLEDGE_ENABLED=false` 即恢复全量 schema（`FullSchemaProvider`），
> 无需改代码或删数据；pgvector/MinIO 容器可单独停掉，检索会记录回退原因而不阻断生成。

### 应用库 / 安全

| 键 | 说明 | 默认 |
|----|------|------|
| `spring.r2dbc.url` | 应用库 R2DBC 连接（用户/会话/模型配置），`APP_DB_R2DBC_URL` 可覆盖 | `r2dbc:mysql://localhost:3306/text2sql` |
| `spring.r2dbc.username` / `password` | 应用库账号（`APP_DB_USERNAME` / `APP_DB_PASSWORD`） | `root` / `root` |
| `spring.sql.init.mode` | 是否由应用建表（`APP_DB_INIT_MODE`）；MySQL 由 docker-compose 建表 | `never` |
| `text2sql.security.jwt-secret` | JWT 签名密钥（≥32 字节，生产用 `TEXT2SQL_JWT_SECRET` 覆盖） | 内置演示值 |
| `text2sql.security.token-ttl-minutes` | 令牌有效期（分钟） | `720` |
| `text2sql.security.issuer` | JWT 签发者 | `text2sql` |

> 应用库通过 **Spring Data R2DBC** 访问（全链路非阻塞）；MySQL 的库表由 **docker-compose 初始化脚本**创建
> （挂载 `db/app-schema.sql`）。测试用 H2（`test` profile，`r2dbc:h2`），无需 MySQL。

### Agent `text2sql.agent.*`

| 键 | 说明 | 默认值 |
|----|------|--------|
| `name` | Agent 名称 | `Text2SqlAgent` |
| `model` | 模型标识（可用 `TEXT2SQL_MODEL` 覆盖） | `dashscope:qwen-plus` |
| `workspace` | AgentScope 工作区目录 | `./agentscope/workspace` |
| `max-context-tokens` | 上下文窗口上限 | `8000` |
| `subagents-enabled` | 注册多智能体子代理（schema_explorer/sql_runner/report_writer） | `true` |
| `sys-prompt-file` | Agent 系统提示词文件（`resources/prompts/`） | `prompts/agent-system.md` |
| `sys-prompt` | 内联系统提示词（非空时覆盖文件） | 空 |

> **提示词集中管理**：所有 LLM 提示词位于 `text2sql-backend/src/main/resources/prompts/`
> （`agent-system.md` / `nl2sql-system.md` / `nl2sql-user.md`），使用 `{{占位符}}` 模板，由
> `prompt.PromptCatalog` 加载渲染；配置文件只保留文件路径。提示词要求**模型最终以简体中文输出**
> （SQL 解释与 Agent 回答）。

切换模型示例（OpenAI 兼容）：

```bash
set TEXT2SQL_MODEL=openai:gpt-4.1
set OPENAI_API_KEY=sk-...
```

## 安全说明

- 只读：连接设置为只读，且仅接受单条 `SELECT` / `WITH` 语句；校验器阻断多语句与 DDL/DML 关键字。
- 授权：每次请求从数据库解析有效角色/权限/数据源/数据范围；JWT 的 `role` claim 仅展示。
  停用账号、重置密码通过 `token_version` 让旧令牌立即失效。
- 防提权：管理员不能授予/继承/分配自己未持有的权限；最后一个 `access.manage` 持有者受保护；
  内置 `admin` 角色不可停用/改父级。
- 资源保护：行数上限 + 查询超时；阻塞 JDBC 一律在 `blockingScheduler`（虚拟线程）上执行，
  不阻塞事件循环。
- 建议：生产环境为数据源配置**只读数据库账号**，并按需开启限流。

## 与 DB-GPT 的对应关系

| DB-GPT | 本工程 |
|--------|--------|
| ConnectorManager（数据源池） | `datasource.DataSourceRegistry` |
| 数据源管理 / 连接测试 | `web.DataSourceController` |
| Schema 抽取 + DBSchemaRetriever（schema RAG） | `schema.SchemaIntrospector` + `SchemaProvider`（`FullSchemaProvider` / `RagSchemaProvider`） |
| ChatWithDB 场景流水线 | `service.Text2SqlPipelineService` + `nl2sql.*` |
| BaseConnector 执行 | `sql.SqlExecutor`（只读 + 限流 + 超时） |
| 知识库（MS-RAG 简化版） | `knowledge.KnowledgeService` + `web.KnowledgeController` |
| ReAct Agent / 多智能体 | AgentScope `HarnessAgent` + `tool.SqlTools` + `HarnessAgentFactory#subagentDeclarations` |
| GBI 图表 | `gbi.ChartInference` → `chart` SSE → 前端 ECharts |
| Serve 层 REST | `web.Text2SqlController` / `DataSourceController` / `KnowledgeController` |

详见 [`docs/nl2sql.md`](docs/nl2sql.md)。

## 说明

- 根目录**未**做 Maven 聚合，前后端各自独立构建。
- 应用库表结构由 `db/app-schema.sql` + `db/app-data.sql` 初始化；已有数据卷用
  `db/migration/2026-11-09-access-control.sql` 幂等迁移（`deploy/bootstrap.sh` 自动执行）。
- AgentScope 工作区中的 `knowledge/SCHEMA.md` 仅作文档保留；**Schema 事实来源是实时内省**。
