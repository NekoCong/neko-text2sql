# Tasks

## 1. 范围描述与提示词注入

- [x] 1.1 新增 `DataScopeDescriber`（`summary` / `promptBlock` / `restricted`），覆盖学生、辅导员、管理员三种角色；验证：新增单元测试断言三种文案，且学生/辅导员的文案不包含范围外的班级、学号或表名
- [x] 1.2 将 `Text2SqlPipelineService.callerContext` 的文案迁移到 `DataScopeDescriber` 并改为复用；验证：`Text2SqlPipelineServiceTest` 通过，且新增断言确认流水线请求仍包含原有的"仅本人/所管班级"过滤提示
- [x] 1.3 在 `resources/prompts/agent-system.md` 增加 `{{data_scope}}` 占位符与回答规则（禁止把权限过滤说成数据不存在、禁止探测范围外存在性、管理员不添加免责声明）；验证：提示词内容测试断言占位符与三条规则均存在
- [x] 1.4 将 `HarnessAgentFactory.create` 改为接收 `DataScope` 并按请求渲染提示词，移除无范围的旧重载并更新调用点与测试；验证：工厂测试断言学生提示含"仅本人"、管理员提示含"不受限"，且渲染结果不再残留 `{{data_scope}}`

## 2. 工具返回范围元数据

- [x] 2.1 让 `query_database` 返回 `{ "result": …, "dataScope": { restricted, role, summary } }` 的信封结构；验证：`SqlToolsTest` 断言两个字段都存在，学生 `restricted=true` 且 summary 不含其他班级，管理员 `restricted=false`
- [x] 2.2 在 `agent-system.md` 说明新的工具返回信封，并声明 `dataScope` 是本次结果的范围依据；验证：提示词测试断言模板同时提到 `result` 与 `dataScope`
- [x] 2.3 更新 `sql_runner` 子智能体的说明，使其从 `result` 读取 SQL 与行数据；验证：`HarnessAgentFactorySubagentsTest` 通过，且断言子智能体提示包含新的字段说明

## 3. 确定性回答兜底

- [x] 3.1 实现 `ScopeAnswerGuard`：对受限账号匹配否定断言（不存在/没有数据/数据库中只有/未找到/没有找到/无数据），已含范围说明或管理员时返回空；验证：单元测试覆盖触发、不触发、已含说明、管理员四种情况
- [x] 3.2 在 Agent SSE 流中累积 `delta` 文本，并在 `done` 之前按守卫结果补发一条澄清 `delta`；验证：组件级测试断言澄清只追加一次且 `done` 仍是最后一个事件，本地学生账号手测可看到追加说明

## 4. 文档与整体验证

- [x] 4.1 更新 `docs/nl2sql.md`（Agent 数据范围披露、工具 `dataScope`、兜底规则），必要时同步 `README.md`；验证：文档描述与本次测试及实际行为一致
- [x] 4.2 运行全量 `mvn test`；验证：全部测试通过，无回归
- [x] 4.3 手工端到端（学生账号）：提问"1 班和 2 班的数学平均分"；验证：回答说明当前仅能访问本人范围，不出现"2 班不存在"或"数据库只有 1 班"的断言
- [x] 4.4 手工回归（同一学生）：切换到流水线模式提同样问题；验证：回答仍包含原有的"结果已按当前账号的数据范围（仅本人）过滤"说明，行为与改动前一致
- [x] 4.5 手工验证（管理员账号）：提问同样的跨班问题；验证：正常回答且不追加范围免责声明

## Workflow follow-up

- 评审通过后按项目流程归档本变更，并确认归档后的 `data-permission` 主规范包含新增的 Agent 范围语义。
