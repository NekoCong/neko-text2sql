# Design

## Context

见 `proposal.md`。当前实现的关键约束：

- 知识库是进程内 `ConcurrentHashMap`（`KnowledgeService`），按 namespace 分块，重启即丢失；embedding 可选，缺失时词法回退。
- Schema 检索是内存版 `RagSchemaProvider`：对「表名 + 列名」做 embedding，top-k 后补外键邻居；无持久化。
- 应用库是 MySQL + R2DBC；目标数据源用 JDBC + Hikari；WebFlux 请求路径上的阻塞操作统一放 `boundedElastic`。
- 前端没有知识库页面，也没有 PDF 渲染依赖；导航只有对话、数据源、数据源权限（管理员）。
- 现有 `KnowledgeController` 的 `/documents` 与 `/search` 接口未被前端使用，可作为兼容别名保留。
- `docker-compose.yml` 现有 `grafana/otel-lgtm` 与 `mysql:8.4` 直连 Docker Hub，国内网络下不稳定。

## Goals / Non-Goals

**Goals:**

- 用 PostgreSQL + pgvector 持久化三类知识与向量，进程重启不丢。
- 三类知识逻辑隔离、统一管理；元数据按数据源、业务知识按数据源或全局、智能体知识按 Agent。
- 入库与解析、embedding 解耦：事务提交后异步处理，状态机可观测、失败可重试。
- 多格式文档可入库，解析器可插拔；版面类文档产出页码与坐标，文本类文档产出字符区间。
- 原文与解析产物持久化到 MinIO，检索命中可回溯到原文具体位置。
- 检索链路可解释、可回退，并严格受调用者数据源授权与表级白名单约束。
- 保持 `SchemaProvider` 接口不变，流水线与 Agent 路径无需改动即可切换。
- 前端提供管理员知识库页面与原文引用预览（角标 → 分页预览 → 高亮）。
- 所有容器镜像使用华为云 SWR 镜像，国内网络可直接拉取。

**Non-Goals:**

- 不引入 Spring AI Alibaba Graph，不重写流水线编排。
- 不改变 SQL 生成策略（候选生成/自修复见文末「后续方向」）。
- 不迁移 MySQL 应用库，不引入 Milvus/Qdrant/Elasticsearch。
- 不做多租户级知识隔离（当前以数据源 + Agent + 角色为边界）。
- v1 不做 Office → PDF 的版面转换，Office/URL 的引用以字符区间 + 文本快照呈现。
- 不实现 OCR 自建流水线，扫描件与图片的版面识别交给 MinerU。

## Decisions

### D1. 独立 PostgreSQL + pgvector 容器，JDBC 访问；镜像统一走华为云

新增 pgvector 服务（端口 `5433`，避免与本地 PostgreSQL 冲突），初始化脚本执行 `CREATE EXTENSION IF NOT EXISTS vector`。应用侧新增专用 `DataSource` + `JdbcTemplate`，不复用应用库 R2DBC，也不复用目标数据源 Hikari 池；所有调用放在 `boundedElastic`。

镜像统一用 `docker.aityp.com` 收录的华为云 SWR 地址，compose 中用 `${REGISTRY_PREFIX}` 变量便于切换回官方源：

| 服务 | 镜像 |
|------|------|
| pgvector | `${REGISTRY_PREFIX:-swr.cn-north-4.myhuaweicloud.com/ddn-k8s}/docker.io/pgvector/pgvector:pg16` |
| MinIO | `${REGISTRY_PREFIX:-swr.cn-north-4.myhuaweicloud.com/ddn-k8s}/docker.io/minio/minio:RELEASE.2025-07-23T15-54-02Z` |
| MySQL（现有） | `${REGISTRY_PREFIX:-swr.cn-north-4.myhuaweicloud.com/ddn-k8s}/docker.io/library/mysql:8.4` |
| otel-lgtm（现有） | `${REGISTRY_PREFIX:-swr.cn-north-4.myhuaweicloud.com/ddn-k8s}/docker.io/grafana/otel-lgtm:latest` |

- **为什么**：国内直连 Docker Hub 常超时；`ddn-k8s` 前缀是 docker.aityp.com 提供的 SWR 同步镜像，`pgvector:pg16` 与 `minio` 的具体 tag 已核对其 RepoTags。用变量而不是硬编码，便于 CI/海外环境切回官方镜像。
- **注意**：arm64 镜像在 docker.aityp.com 上带 `-linuxarm64` 后缀，compose 默认按 amd64 tag 拉取；Apple Silicon 需覆盖 tag 或设置 `platform`。
- **备选**：迁移应用到 PostgreSQL（改动面大，R2DBC/建表/测试全受影响）；Milvus（重，用户明确排除）；MySQL 向量 UDF（生态与召回能力弱）；自建 registry 同步（运维成本高，demo 不需要）。

### D2. 逻辑三库，物理共用一套表；新增文档表承载原文与解析产物

用 `knowledge_item` + `knowledge_document` + `knowledge_chunk` + `knowledge_term_mapping` 承载三类知识：

- `knowledge_item`：`id`、`knowledge_type`（`METADATA`/`BUSINESS`/`AGENT`）、`scope`（`GLOBAL`/`DATASOURCE`/`AGENT`）、`datasource_id`、`agent_id`、`item_key`、`title`、`content`、`payload jsonb`、`status`、`failure_stage`、`failure_reason`、`embedding_model`、`created_at`、`updated_at`、`version`。
- `knowledge_document`：`id`、`item_id`、`source_type`（`UPLOAD`/`URL`）、`source_url`、`filename`、`mime_type`、`size_bytes`、`sha256`、`object_key`、`derived_prefix`、`page_count`、`parse_engine`、`parse_engine_version`、`parse_status`、`parse_failure_reason`、`fetched_at`、`created_at`。
- `knowledge_chunk`：`id`、`item_id`、`document_id`、`chunk_index`、`content`、`page_no`、`bbox int[]`、`char_start`、`char_end`、`quote`、`embedding vector(1024)`、`created_at`；`(item_id, chunk_index)` 唯一。
- `knowledge_term_mapping`：`datasource_id`、`term`、`target_type`（`TABLE`/`COLUMN`）、`table_name`、`column_name`、`description`；`(datasource_id, term)` 唯一。

索引：`embedding` 上建 HNSW（`vector_cosine_ops`）；`(knowledge_type, datasource_id, agent_id, status)` 与 `(document_id)` 建 B-tree。

- **为什么**：三类知识的生命周期、状态机、分块与向量逻辑完全一致，分表会复制三套代码；类型与维度过滤即可实现「分开建库」的检索隔离。文档表单独拆出，是因为一个知识条目可以有原文、解析状态与多个派生对象，混进 item 会让 item 变成宽表。
- **备选**：每类一张物理表（迁移与查询重复）；每条知识一行向量（大文档分块能力差）；原文存数据库大字段（备份与流式读取都差）。

### D3. 解析 + embedding 生命周期：事务后事件 + 状态机

写入流程：`upsert item` + `upsert document`（事务内）→ 发布 `KnowledgeItemSavedEvent` → `@TransactionalEventListener(AFTER_COMMIT)` → 异步执行「解析 → 分块 → embedding」→ 写 `knowledge_chunk` → 更新状态。

- 条目状态：`PENDING` → `PARSING` → `EMBEDDING` → `READY` | `FAILED`；失败记录 `failure_stage`（`PARSE`/`CHUNK`/`EMBED`）与 `failure_reason`。
- 文档状态：`PENDING` → `PARSING` → `PARSED` | `FAILED`。
- 重试按阶段重入：`PARSE` 失败从解析开始，`EMBED` 失败复用已有分块；重试前清理旧的同阶段产物，保证幂等。

- **为什么**：避免写入请求被解析与 embedding 阻塞，也避免事务未提交就产生副作用。按阶段重试可以避免「embedding 失败却重新花一次 MinerU 配额」。
- **备选**：同步处理（写延迟不可控）；CDC/outbox 表（当前规模过重）。事件监听器本身不做重试风暴，重试由显式接口 + 有限次调度触发。

### D4. embedding 模型与维度固定为 1024

沿用 AgentScope `EmbeddingModel`（DashScope `text-embedding-v3`，1024 维），通过配置注入；`knowledge_chunk.embedding` 固定 `vector(1024)`。模型名与维度写入 `knowledge_item.embedding_model`，便于未来识别需要重建的条目。

- **为什么**：与现有 RAG 配置一致，避免引入第二套 embedding SDK；固定维度让 HNSW 索引简单可靠。
- **备选**：多维度动态列（pgvector 不支持按行不同维度，需多表/多列）；本地模型（部署成本高）。

### D5. 检索链路：改写 → 双路召回 → 表列补齐 → 外键一跳 → LLM 选表

1. **查询改写**：先用 `knowledge_term_mapping` 做规则扩展；若开启 LLM 改写，则用低温度、短超时模型补全术语与意图，失败回退原问题。
2. **双路召回**：业务知识与当前 Agent 的智能体知识并行向量召回，单路失败不影响另一路。
3. **元数据召回**：对表摘要（表名 + 字段名 + 字段语义）做向量召回，取 top-k 候选表。
4. **列跟随表**：按候选表补齐完整字段，不返回孤立列。
5. **外键补一跳**：沿 FK 补关联表，补入表再次过白名单。
6. **LLM 精选**：把候选表清单交给模型选出最终集合，输出必须落在候选集合内；失败/超时回退规则候选集。
7. **渲染**：在 `schema-char-budget` 内输出 DDL + 关系，超预算按相关度截断并标注省略数量。

- **为什么**：DataAgent 的实践表明「表级召回 + 列跟随 + 外键补全」比纯列/纯表 top-k 更稳；LLM 精选只在候选集内做收敛，避免开放式生成失控。
- **备选**：纯向量 top-k（列漂移、JOIN 断链）；纯 LLM 选表（不可控、成本高）。

### D6. 范围裁剪在检索前完成

检索入口先解析调用者的 `DataScope` 与数据源授权，得到允许的表名集合；SQL 查询在 `datasource_id` 与允许表名上过滤，业务知识按 `scope=GLOBAL` 或当前数据源过滤，智能体知识按 `agent_id` 过滤。调试接口仅管理员可用，且返回内容同样裁剪。

- **为什么**：知识检索是新的元数据泄露面，必须在召回阶段而不是渲染阶段裁剪。
- **备选**：检索后过滤（向量片段已进入内存/日志，仍有泄露风险）。

### D7. 用 `KnowledgeSchemaProvider` 替换内存版 RAG Provider

新增 `KnowledgeSchemaProvider implements SchemaProvider`，由 `text2sql.knowledge.enabled=true` 激活；关闭时继续使用 `FullSchemaProvider`。`RagSchemaProvider` 标记为遗留并移除（或保留一个版本作为对照，默认不装配）。流水线与 `SqlTools` 不变。

- **为什么**：接口不变是这次改造能低风险落地的关键；Agent 路径自动受益。
- **备选**：直接改 `RagSchemaProvider`（耦合内存索引与 pgvector，难回退）。

### D8. API 与前端

后端在 `/api/text2sql/knowledge` 下扩展：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/items?type=&datasourceId=&agentId=&status=` | 条目列表 |
| POST/PUT/DELETE | `/items`、`/items/{id}` | 条目 CRUD（管理员） |
| POST | `/metadata/sync?datasourceId=` | 元数据同步（管理员） |
| POST | `/items/{id}/retry` | 按失败阶段重试（管理员） |
| GET/POST/DELETE | `/term-mappings` | 字段级语义映射（管理员） |
| POST | `/documents`（multipart） | 上传文档（管理员） |
| POST | `/documents/url` | 网页导入（管理员） |
| GET | `/documents?type=&datasourceId=&agentId=&status=` | 文档列表与解析状态 |
| POST | `/documents/{id}/reparse?engine=` | 换引擎重新解析（管理员） |
| GET | `/documents/{id}/preview-url` | 取短时效预览地址（先做范围校验） |
| GET | `/documents/{id}/content` | 取规范化解析结果（文本模式预览） |
| POST | `/search` | 按调用者范围检索，命中带引用 |
| POST | `/search/debug` | 检索轨迹（管理员） |

`/documents` 旧语义与新的文档上传路径冲突，旧接口改为 `/legacy/documents` 兼容别名，标记 deprecated。

前端新增 `KnowledgeView.vue`、`api/knowledge.ts`、路由 `/knowledge`（`meta.admin=true`）与管理员导航项「知识库」；页面用 `el-tabs` 分三类，文档型条目展示文件名、解析引擎、页数与状态，顶部提供元数据同步、文档上传、网页导入与检索调试抽屉。对话侧新增 `CitationBadge.vue` 与 `CitationDrawer.vue`。

- **为什么**：沿用现有 Element Plus + Academic Clarity 设计语言，管理员入口与数据源权限一致。
- **备选**：独立管理后台（超出当前单页应用结构）。

### D9. 测试策略：接口隔离 + 可选容器集成测试

定义 `VectorStore`、`ObjectStore`、`DocumentParser`、`MinerUClient` 四个接口。生产实现分别是 `PgVectorStore`、`MinioObjectStore`、`TikaDocumentParser`/`MinerUDocumentParser`、`HttpMinerUClient`；测试用 `InMemoryVectorStore`、`InMemoryObjectStore`、`StubDocumentParser`、录制的 `content_list.json` fixture。`mvn test` 不依赖 Docker、MinIO 或 MinerU 网络。

pgvector 的 SQL、索引与事务行为用 `@Tag("pgvector")` + Testcontainers（或本地 compose）在单独 profile 验证。

- **为什么**：保持现有 H2 测试零外部依赖；同时不放弃对真实 pgvector 的验证。解析器的坐标映射是最容易出错的部分，用固定 fixture 做纯单元测试最划算。
- **备选**：全部用 Testcontainers（CI 需 Docker，现有测试会变慢/变脆）；真实调用 MinerU（不可重复、有配额）。

### D10. 文档解析：Tika 默认，MinerU 负责版面

`DocumentParser` 按「格式 + 配置」选择实现，解析结果统一为 `ParsedDocument`：

```
ParsedDocument {
  plainText, blocks[]
  Block { blockId, type, text, pageNo(1-based), bbox[x0,y0,x1,y1](0-1000), charStart, charEnd, imagePath? }
}
```

- **TikaDocumentParser（默认，本地）**：`org.apache.tika:tika-core` + `tika-parsers-standard-package` 3.x，`AutoDetectParser` + `BodyContentHandler` 抽取 docx/pptx/xlsx/txt/md/csv/html 文本。按段落切块并计算 `charStart/charEnd`，无 `pageNo/bbox`。网页 URL 也走这条路径：抓取 HTML → Tika 抽正文 → 快照存 MinIO。
- **MinerUDocumentParser（PDF / 图片 / 复杂版式）**：走精准解析 API（需 Token），因为需要 `page_idx` 与 `bbox` 才能做原文高亮。
  1. `POST /api/v4/file-urls/batch`（`files[{name,data_id}]`、`model_version=vlm`）→ `batch_id` + `file_urls`；
  2. `PUT` 文件字节到返回的 OSS 上传链接（**不要求 MinIO 公网可达**，解决本地对象存储无法被云端回源的问题）；
  3. 轮询 `GET /api/v4/extract-results/batch/{batch_id}` 至 `done`/`failed`；
  4. 下载 `full_zip_url`，解压取 `full.md`、`*_content_list.json`、`layout.json`；
  5. 把 `content_list.json` 映射为 blocks：`page_idx + 1 → pageNo`，`bbox` 原样保留（已是 0–1000 归一化），`text` 即片段文本。
- **选择与回退**：`pdf-engine`、`image-engine` 可配置（`mineru`/`tika`）。MinerU 不可用或未配 Token 时，PDF/图片解析**失败并给出可读原因**，不静默产出无引用片段；文本类格式始终可用 Tika。
- **限制前置校验**：MinerU 精准解析上限 200MB / 200 页；超限在提交前拒绝。解析超时、轮询上限、并发数均可配置。
- **记录**：`parse_engine` + `parse_engine_version` 写库，重新解析可换引擎并替换旧片段。

- **为什么**：Tika 覆盖 90% 的 Office/文本场景且无外部依赖；MinerU 的 `content_list.json` 天然提供段落级 bbox 与页码，正好满足「原文高亮」这个硬需求，比自建 PDF 版面分析便宜得多。Agent 轻量 API 虽免 Token，但只返回 Markdown、≤20 页且无 bbox，不满足引用要求，故不用。
- **备选**：纯 Tika（PDF 只能拿文本流，无法定位到页面坐标）；自建 MinerU 本地部署（显存与运维成本高，可后续替换 `MinerUClient` 指向自建实例）；PDFBox 自算坐标（只对文本型 PDF 有效，扫描件与复杂版式不行）。

### D11. 原文与派生内容存 MinIO，库里只存对象键

新增 `minio` 服务（API `9000`、控制台 `9001`，命令 `server /data --console-address ":9001"`，数据卷持久化），启动时由 `MinioBucketInitializer` 幂等创建私有桶 `text2sql-knowledge`。SDK 用 `io.minio:minio`。

对象键布局：

```
originals/{scope}/{ownerId}/{documentId}/{safeFilename}   # 原文
derived/{documentId}/content.json                          # 规范化 blocks
derived/{documentId}/full.md                               # MinerU/Tika 文本
derived/{documentId}/pages/{pageNo}.png                    # 可选：引擎提供的页面渲染
snapshots/{documentId}/source.html                         # 网页抓取快照
```

- 数据库只保存 `object_key` / `derived_prefix`，**不保存预签名 URL**（会过期，且等于把授权写死）。
- 预览时后端先做范围校验，再用 `GetPresignedObjectUrlArgs` 签发 GET 地址，默认 10 分钟；过期后前端重新请求。
- 桶保持私有，不配置匿名读；删除知识时按前缀级联删除原文与派生对象。
- 同一 `sha256` 的重复上传复用已有对象，避免重复存储。

- **为什么**：原文是二进制、可能上百 MB，放对象存储比放数据库或本地磁盘更合适；预签名 URL 让前端可以直接用 `pdfjs-dist` 拉流，不需要后端代理大文件。
- **备选**：后端流式代理（授权更严，但要处理 Range 请求与大文件并发，v1 成本高）；本地文件系统（容器重建即丢，违背持久化目标）；直接公开桶（不可接受）。

### D12. 引用模型：按页分块，片段自带定位信息

分块规则改为「页内连续块合并」：同一页的相邻 blocks 合并到 chunk 大小上限，**不跨页**。因此每个 chunk 只有一个 `page_no`，`bbox` 取这些块的并集，`char_start/char_end` 取首块起点到末块终点，`quote` 存片段原文（用于展示与兜底匹配）。

检索命中返回的引用结构：

```
Citation {
  documentId, filename, mimeType, sourceType,
  pageNo, bbox, charStart, charEnd, quote,
  previewUrl, previewExpiresAt, fetchedAt
}
```

- 只有 `BUSINESS` / `AGENT` 的文档型条目产生引用；`METADATA` 条目没有文件，不产生预览。这样既符合直觉，也避免「表级白名单外的内容通过整篇文档预览泄露」。
- 引用随检索结果一起返回，并随 SSE 流以 `citations` 事件下发；前端按命中顺序编号。
- 引用与片段的对应关系在服务端一次性计算，前端不再猜位置。

- **为什么**：跨页合并会让 bbox 无法用一个矩形表达，高亮就得处理多矩形；限制为页内合并后，一个 chunk 对应一个可高亮区域，实现与测试都简单。`quote` 同时是「原文依据」的可见文本，也是坐标漂移时的兜底。
- **备选**：chunk 内保存 block 列表（表达力更强，前端要渲染多个高亮框，v1 收益不大）；整篇文档作为一个向量（召回粒度太粗）。

### D13. 前端预览：pdfjs-dist 渲染 + bbox 覆盖层

- `pdfjs-dist` 动态引入（仅在打开抽屉时加载），worker 用 Vite 的 `?url` 导入，避免打进首屏包。
- **PDF 模式**：`getDocument(previewUrl)` → 渲染目标页到 `<canvas>` → 在 canvas 上叠一个绝对定位层，高亮框按 `left = bbox.x0/1000 * viewport.width`、`top = bbox.y0/1000 * viewport.height`、宽高同理换算。旋转页用 `viewport.convertToViewportPoint` 处理，保证缩放/旋转后仍对齐。
- **图片模式**：`<img>` 按 `naturalWidth/naturalHeight` 等比缩放，高亮框用同样的百分比定位。
- **文本模式**（Office / 网页快照）：渲染 `content.json` 的纯文本，按 `charStart/charEnd` 包 `<mark>`；偏移失配时回退到首个 `quote` 匹配位置。
- 抽屉打开即跳到 `pageNo` 并滚动到高亮；支持关闭高亮、翻页、缩放与「下载原文」。
- 引用不可用时展示提示 + `quote` 文本，不显示空白画布。

- **为什么**：pdfjs-dist 是 Mozilla 维护的 PDF 渲染事实标准，能直接吃预签名 URL；bbox 覆盖层比修改 PDF 内容简单且无副作用。MinerU 的 bbox 已是 0–1000 归一化，换算只需要视口尺寸，不需要原始页面尺寸。
- **备选**：服务端把 PDF 转图片再前端标注（多一次转换与存储，且丢文本选择）；`<iframe>` + 浏览器内置 PDF 阅读器（无法注入高亮）；Office 在线预览服务（外部依赖、不可控）。

## Risks / Trade-offs

- **MinerU 外部依赖** → Token 缺失、配额耗尽、网络失败都会让 PDF/图片入库失败。对策：解析器可插拔、失败原因可读、可按阶段重试、可切回 Tika（仅文本型 PDF）；不静默产出无引用片段。
- **MinerU 文件限制** → 200MB / 200 页，超限前置拒绝；大文件解析耗时长，用异步 + 轮询预算 + 并发上限控制。
- **坐标语义漂移** → MinerU bbox 为 0–1000 归一化、`page_idx` 从 0 开始，转换集中在 `CitationMapper` 一处并配 fixture 单测；旋转页用 pdfjs viewport 校正。
- **Office 引用精度** → v1 只有字符区间，没有版面框；用户看到的是文本快照高亮。若后续需要页级高亮，再引入 Office → PDF 转换或 MinerU 页面渲染图。
- **预签名 URL 泄露** → 短时效（10 分钟）+ 签发前范围校验 + 私有桶；不把 URL 落库、不写日志。
- **整篇预览与表级白名单** → 只有文档型知识（业务/智能体）有预览，元数据不产生文件引用，规避越权面。
- **MinIO 单节点** → 仅适用于开发/演示；生产需分布式部署或换外部 S3 兼容存储，配置项保持 S3 语义以便替换。
- **存储增长** → 原文 + 派生 + 向量三份数据；用 `sha256` 去重、删除级联、按数据源配额告警。
- **前端包体** → `pdfjs-dist` 体积大，必须动态加载；聊天首屏不引入。
- **维度锁死 1024** → 更换 embedding 模型需重建向量；在 `knowledge_item.embedding_model` 记录模型，提供「按模型筛选 + 重建」运维路径。
- **异步处理延迟** → 新知识入库后短时间内检索不到；列表明确显示 `PENDING/PARSING/EMBEDDING`，未就绪不参与召回。
- **双写一致性** → 事务后事件可能丢失（进程崩溃）；用状态机 + 定时补偿扫描超时条目重试。
- **pgvector 索引召回率** → HNSW 参数（`m`、`ef_construction`）与 top-k 需按数据量调优；先给默认值并在检索调试中暴露候选。
- **知识泄露** → 检索前裁剪 + 调试接口管理员限定 + 日志脱敏；新增权限场景测试。
- **Windows Docker** → 文档注明 `docker compose up -d pgvector minio`，并允许通过 `PGVECTOR_URL`、`MINIO_ENDPOINT` 指向已有实例。
- **成本** → 查询改写与 LLM 选表增加模型调用；默认开启规则改写、LLM 改写可配置关闭，选表只在候选数超过阈值时触发。

## Migration Plan

1. `docker compose up -d pgvector minio`，确认 `vector` 扩展、知识库表结构与私有桶初始化成功。
2. 部署后端（`text2sql.knowledge.enabled=false`），接口可用但检索仍走全量 schema；配置 `MINERU_TOKEN`（可选）。
3. 管理员在知识库页面执行元数据同步，等待状态变为 `READY`；补充业务知识与术语映射。
4. 上传一份 PDF 与一份 Word，确认解析状态、页码/字符区间与引用预览高亮都正确。
5. 打开 `text2sql.knowledge.enabled=true`，观察检索调试、引用返回与回退日志。
6. 回滚：关闭开关即回到全量 schema；pgvector 与 MinIO 容器可保留，不影响 MySQL 应用库。

## 后续方向：SQL 生成（本变更不实施）

当前是「一次生成 + 校验 + 执行」，问题在于模型没有执行反馈，错误只能靠重试。建议按收益排序：

1. **检索增强（本变更提供）**：把三类知识与表列召回注入提示，先降低「缺表/缺列/口径错」。
2. **候选生成 + AST 校验 + 执行反馈自修复（推荐下一步）**：生成 2-3 个候选，用 `SqlSafetyValidator` 与 `DataScopeRewriter` 预检，执行 `EXPLAIN` 或带 `LIMIT 0` 的试跑；失败把错误回灌模型做一次修复，仍失败则返回可读原因。成本可控，收益直接。
3. **Planner / SQL 分离**：先让模型输出结构化查询计划（指标、维度、过滤、连接路径），再由确定性代码或模板生成 SQL；适合指标口径固定的场景。
4. **受限解码/语法约束**：用方言语法约束解码，正确率上限高但工程成本与模型依赖大，暂不优先。

推荐 1 + 2，作为下一个 change 实施；Graph 编排（外层 Workflow、节点 Agent）可在 SQL 生成稳定后再引入。

## Open Questions

- MinerU 用云 API 还是后续替换为自建实例？（`MinerUClient` 已抽象，切换不影响上层）
- 原文预览用预签名直连还是后端代理？（v1 取预签名；若后续要审计每次原文访问，再切代理）
- 生产 embedding 模型是否仍用 DashScope，还是切到自建/其它 provider？（配置可切换，不影响本设计）
- HNSW 参数与 top-k 的初始值需按真实知识量压测后确定。
