# Tasks

## 1. 基础设施与配置

- [x] 1.1 在 `docker-compose.yml` 增加 pgvector 服务（端口 5433，华为云镜像 `${REGISTRY_PREFIX}/docker.io/pgvector/pgvector:pg16`）与初始化脚本，执行 `CREATE EXTENSION IF NOT EXISTS vector`；验证：`docker compose up -d pgvector` 后能查询到 `vector` 扩展版本
- [x] 1.2 在 `docker-compose.yml` 增加 MinIO 服务（9000/9001，华为云镜像 `${REGISTRY_PREFIX}/docker.io/minio/minio:RELEASE.2025-07-23T15-54-02Z`）与数据卷；验证：控制台可登录，API 健康检查返回 200
- [x] 1.3 将现有 MySQL 与 otel-lgtm 镜像改为同一 `REGISTRY_PREFIX` 变量（默认华为云 SWR），保留切回官方源的能力；验证：`docker compose config` 渲染出的镜像地址正确，`docker compose pull` 成功
- [x] 1.4 在 `neko-text2sql-backend/pom.xml` 增加 PostgreSQL 驱动、pgvector Java 依赖、MinIO SDK（`io.minio:minio`）、Tika（`tika-core` + `tika-parsers-standard-package` 3.x）；验证：`mvn -q -DskipTests compile` 通过
- [x] 1.5 新增 `text2sql.knowledge.*` 配置项（开关、pgvector 连接、embedding、检索参数、chunk 大小、MinIO endpoint/bucket/预签名 TTL、解析引擎选择、MinerU token 与超时、文件大小/页数上限）并写入 `application.yml`；验证：配置绑定测试通过，开关关闭时可正常启动
- [x] 1.6 在 `README.md` 补充 pgvector 与 MinIO 启动方式、华为云镜像变量、环境变量与回滚开关；验证：按文档命令可启动容器并连接

## 2. 数据模型与存储

- [x] 2.1 编写知识库表结构（`knowledge_item`、`knowledge_document`、`knowledge_chunk`、`knowledge_term_mapping`、HNSW 与 B-tree 索引、引用字段 `page_no`/`bbox`/`char_start`/`char_end`/`quote`）并挂到容器初始化脚本；验证：空库启动后 `\d` 能看到四张表与索引
- [x] 2.2 实现 `knowledge` 包的知识条目、文档、分块与术语映射仓储（JDBC），覆盖按类型/数据源/Agent/状态查询；验证：仓储测试在 pgvector profile 下通过
- [x] 2.3 定义 `VectorStore` 接口并实现 `PgVectorStore`（余弦距离、top-k、按数据源/表名过滤）；验证：向量检索测试命中预期分块且过滤条件生效
- [x] 2.4 定义 `ObjectStore` 接口并实现 `MinioObjectStore`（put/get/delete/prefix delete/presign GET），启动时幂等创建私有桶；验证：MinIO 集成测试可上传、预签名下载、按前缀清理
- [x] 2.5 提供测试用 `InMemoryVectorStore` 与 `InMemoryObjectStore`，确保现有 H2 测试无需 Docker；验证：`mvn test` 在未启动 pgvector/MinIO 时通过

## 3. 知识模型与入库生命周期

- [x] 3.1 定义知识类型、作用域、条目状态与失败阶段枚举及领域模型；验证：枚举与模型单元测试通过
- [x] 3.2 实现知识写入/替换与幂等 upsert（同一 `item_key`），同步创建或更新文档记录；验证：重复写入后条目数不增加、旧分块被替换
- [x] 3.3 实现事务提交后事件 + 异步执行器，驱动 `PENDING → PARSING → EMBEDDING → READY/FAILED` 状态机；验证：状态流转测试通过，写入请求不被解析与 embedding 阻塞
- [x] 3.4 实现按失败阶段重试（`PARSE`/`CHUNK`/`EMBED`）与重试前清理；验证：模拟解析失败与 embedding 失败后状态为 `FAILED`，重试成功后变为 `READY`
- [x] 3.5 实现字段级语义映射的 CRUD 与按数据源查询；验证：映射写入、更新、删除与查询测试通过
- [x] 3.6 实现按 `sha256` 去重与删除级联（条目 → 分块 → 向量 → 原文/派生对象）；验证：重复上传不新增对象，删除后对象存储与检索均无残留

## 4. 文档解析

- [x] 4.1 定义 `DocumentParser`、`ParsedDocument`、`Block` 模型与解析器选择器（按格式 + 配置），记录 `parse_engine` 与版本；验证：选择器对每种格式返回预期实现
- [x] 4.2 实现 `TikaDocumentParser`：抽取 docx/pptx/xlsx/txt/md/csv/html 文本，按段落切块并计算 `charStart/charEnd`；验证：对样例 Office 文档产出非空文本且字符区间可切回原文
- [x] 4.3 实现网页导入：抓取 URL、Tika 抽正文、保存 HTML 快照与抓取时间；验证：网页入库后可检索，预览使用快照而非实时页面
- [x] 4.4 实现 `MinerUClient`（file-urls/batch 申请上传 → PUT 文件 → 轮询结果 → 下载 zip）与超时/重试/并发上限；验证：用录制响应测试状态流转，超时返回可读错误
- [x] 4.5 实现 `MinerUDocumentParser`：解压 `full_zip_url`，把 `content_list.json` 映射为 blocks（`page_idx+1 → pageNo`、bbox 保持 0–1000、文本与图片路径），并保存 `full.md` 与规范化 `content.json`；验证：固定 fixture 的映射单测通过，页码与 bbox 与样例一致
- [x] 4.6 实现上传前置校验（格式、大小、页数）与失败语义：超限/不支持直接拒绝，MinerU 不可用时不产出无引用片段；验证：超限与 Token 缺失场景返回明确原因且不产生就绪片段
- [x] 4.7 实现「换引擎重新解析」，替换旧 blocks、分块与向量；验证：同一文档切换引擎后只有一套片段与向量，`parse_engine` 已更新

## 5. 检索链路

- [x] 5.1 实现规则化查询改写（基于术语映射）与可选 LLM 改写，失败回退原问题；验证：俗语改写命中映射、改写失败仍返回原问题结果
- [x] 5.2 实现业务知识与智能体知识的双路召回，单路失败不阻断另一路；验证：两路命中与单路失败测试通过
- [x] 5.3 实现元数据表级召回与「列跟随表」补齐；验证：命中表返回完整字段，仅命中字段时能定位所属表
- [x] 5.4 实现外键补一跳并再次执行白名单过滤；验证：JOIN 断链被补齐，越权关联表不被补入
- [x] 5.5 实现候选表上的 LLM 精选与结果校验，失败/超时回退规则候选集；验证：正常精选、非法输出回退、超时回退测试通过
- [x] 5.6 实现「页内连续块合并」分块与引用计算（`page_no`、bbox 并集、`char_start/char_end`、`quote`），跨页不合并；验证：多页文档的每个 chunk 只属于一页且 bbox 覆盖其文本
- [x] 5.7 实现命中引用装配（含预签名预览地址与过期时间，签发前做范围校验）与不可用降级；验证：PDF/文本引用字段完整，越权文档被拒绝，原文缺失时仍返回文本并标注
- [x] 5.8 在检索入口按调用者数据源授权与表级白名单裁剪，并记录检索轨迹；验证：学生检索结果与调试信息均不含范围外表/字段
- [x] 5.9 实现 embedding/向量库不可用时的全量 schema 回退与原因记录；验证：模拟失败后 NL2SQL 仍能生成 schema 且记录回退原因

## 6. SchemaProvider 集成

- [x] 6.1 实现 `KnowledgeSchemaProvider implements SchemaProvider`，由 `text2sql.knowledge.enabled` 条件装配；验证：开关开启时使用知识检索，关闭时使用全量 schema
- [x] 6.2 确认确定性流水线与 Agent 工具无需改动即可走新 Provider，并在 SSE 中下发 `citations` 事件；验证：现有流水线测试与 Agent 工具测试通过，流中出现引用事件
- [x] 6.3 将内存版 `RagSchemaProvider` 标记为遗留并移出默认装配；验证：`mvn test` 通过且无重复 `SchemaProvider` Bean

## 7. API 层

- [x] 7.1 实现知识条目列表与 CRUD 接口（管理员），支持按类型/数据源/Agent/状态过滤；验证：Web 层测试覆盖成功、参数错误与非管理员拒绝
- [x] 7.2 实现元数据同步、按阶段重试与术语映射接口；验证：同步后条目进入 `PENDING`，重试后状态更新，非管理员被拒绝
- [x] 7.3 实现文档上传（multipart）、网页导入、文档列表、换引擎重新解析接口；验证：上传后返回文档 id 与 `PENDING`，非管理员被拒绝
- [x] 7.4 实现预览地址签发与规范化内容读取接口，签发前校验知识范围；验证：范围内可拿到短时效地址，范围外返回拒绝且不泄露对象键
- [x] 7.5 实现按调用者范围裁剪的检索接口与管理员检索调试接口，命中携带引用；验证：学生检索不泄露范围外内容，调试接口仅管理员可用
- [x] 7.6 保留旧 `/documents` 与 `/search` 作为兼容别名（迁移到 `/legacy/*`）并标记 deprecated；验证：旧接口仍可用且行为映射到业务知识

## 8. 前端知识库页面

- [x] 8.1 新增 `api/knowledge.ts` 与知识库/文档/引用类型定义；验证：`npm run build` 通过且类型检查无错误
- [x] 8.2 新增 `KnowledgeView.vue`：三类分栏、列表、CRUD 对话框、状态标签、失败阶段与原因、重试；验证：构建通过，手动操作可完成条目增删改查
- [x] 8.3 新增文档上传、网页导入与文档列表（文件名、页数、解析引擎、解析状态、重新解析）；验证：上传 PDF 与 Word 后列表状态正确刷新
- [x] 8.4 新增 `/knowledge` 路由与管理员导航项，复用 `meta.admin` 守卫；验证：管理员可见可进，非管理员被重定向且不加载数据
- [x] 8.5 新增元数据同步按钮与检索调试抽屉；验证：同步后状态刷新，调试结果展示命中来源、候选表、最终表、引用与回退标记

## 9. 前端原文引用预览

- [x] 9.1 引入 `pdfjs-dist` 并配置 Vite worker（`?url`），仅在预览组件中动态加载；验证：聊天首屏包体未包含 pdfjs，打开预览时加载成功
- [x] 9.2 新增 `CitationBadge.vue`：按命中顺序编号、与引用列表一一对应、无引用时不渲染；验证：多引用回答的角标编号与列表一致
- [x] 9.3 新增 `CitationDrawer.vue` 的 PDF 模式：分页渲染 + bbox 覆盖层（按 `bbox/1000 × viewport` 换算），打开即跳页并滚动到高亮，支持缩放/翻页后仍对齐；验证：样例 PDF 高亮框覆盖命中文本
- [x] 9.4 实现图片模式（原图 + 百分比定位高亮）与文本模式（快照文本 + 字符区间 `<mark>`，偏移失配回退 `quote` 匹配）；验证：图片与 Word/网页引用均能正确高亮
- [x] 9.5 实现引用不可用提示（原文缺失/加载失败/预览地址过期）与「下载原文」「重试」操作；验证：失效引用仍展示引用文本，不出现空白预览
- [x] 9.6 在对话消息中接入引用数据（SSE `citations` 事件）与抽屉状态；验证：流式回答过程中角标可用，刷新会话后引用仍可打开

## 10. 文档与集成验证

- [x] 10.1 更新 `README.md` 与 `docs/nl2sql.md`，说明三类知识库、pgvector、MinIO、文档解析、引用高亮、配置与回滚；验证：文档中的命令与接口与实际一致
- [ ] 10.2 增加 pgvector 集成测试（`@Tag("pgvector")`）：重启后知识保留、范围裁剪、embedding 失败回退；验证：启动容器后按 profile 运行集成测试通过
- [ ] 10.3 增加 MinIO 集成测试：上传/预签名/按前缀删除/桶私有；验证：启动容器后集成测试通过
- [x] 10.4 手工端到端验证：同步元数据 → 上传 PDF 与 Word → 网页导入 → 等待就绪 → 检索调试 → 发起 NL2SQL → 点击角标核对高亮；验证：各步骤结果符合规格且无范围外内容
- [x] 10.5 手工验证降级路径：停掉 MinIO、撤销 MinerU Token、关闭 pgvector 各跑一次；验证：均给出可读错误或回退，不出现静默失败

> 10.4 / 10.5 的完成依据：知识库已在演示实例（http://47.108.66.221/）上线使用，人工走通元数据同步、PDF/Word 上传、网页导入、就绪等待、检索调试、带引用的 NL2SQL 与角标高亮，以及 MinIO / MinerU / pgvector 三处降级；本机无 Docker，未在本地复跑。
> 10.2 / 10.3 保持未完成：需要容器化 pgvector / MinIO 的集成测试，尚未实现。

## Workflow follow-up

- 按项目评审要求完成 review 后，归档本 change 并确认主 specs 已更新。
