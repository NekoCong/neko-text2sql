# Design

## Context

见 `proposal.md`。关键现状（已核对代码）：

- `Text2SqlAgentService.stream` 把 `DataScope` 放进 AgentScope `RuntimeContext`，`SqlTools` 以未标注 `@ToolParam` 的参数接收它，模型无法伪造身份；SQL 执行前由 `DataScopeRewriter` 强制过滤。
- `HarnessAgentFactory.create(model)` 使用固定系统提示词，**没有**调用者范围信息。
- `SqlTools.queryDatabase` 直接把 `Text2SqlResult` 序列化为 JSON，**没有**范围元数据。
- `Text2SqlPipelineService.callerContext(scope)` 已能生成"学生/辅导员/管理员"的可读说明，但是私有方法，只用于流水线生成阶段。
- `PromptCatalog.render(location, variables)` 支持 `{{name}}` 占位符，可直接用于按请求渲染提示词。
- `AgentEventPresenter` 把 `TextBlockDeltaEvent` 映射成 SSE `delta`；控制器在流末尾追加 `done`。

因此执行层不需要改动，缺口在"让模型知道范围"和"回答语义"。

## Goals / Non-Goals

**Goals:**

- 每次 Agent 请求都把调用者范围（角色、是否受限、行级范围）注入系统提示词，且与流水线使用同一份文案。
- 工具返回带机器可读的 `dataScope`，模型能在回答中引用。
- 非管理员回答禁止把权限过滤误述为数据缺失；禁止探测范围外存在性。
- 用确定性兜底保证最终回答至少包含范围澄清，即使模型忽略提示词。
- 不泄露范围外表/班级/学号。

**Non-Goals:**

- 不改变权限**执行**逻辑（表级白名单、行级重写、数据源授权保持不变）。
- 不新增 HTTP 接口，不改变前端 SSE 事件契约。
- 不为范围外数据做存在性校验或反向查询。
- 不重写 Agent 的多智能体编排。
- 不改动流水线的回答文本（只复用其范围描述）。

## Decisions

### D1：统一的范围描述组件（共享给流水线与 Agent）

新增 `security/DataScopeDescriber`（或 `service` 包）提供：

- `summary(scope)`：短描述，用于兜底澄清，例如 `学生（仅本人：学号 2023002，班级 1）`。
- `promptBlock(scope)`：完整提示词块，包含"问题中的我/我的指本人""结果只覆盖该范围"等过滤提示。
- `restricted(scope)`：`!scope.isAdmin()`。

把 `Text2SqlPipelineService.callerContext` 的文案迁移进来，流水线改为调用它，保证两处不漂移。

**替代方案**：在 `agent-system.md` 里写死一段通用说明——无法表达学生自己的学号/班级，也容易与流水线文案分叉；否决。

### D2：按请求渲染 Agent 系统提示词

`HarnessAgentFactory.create(Model, DataScope)` 使用
`prompts.render(file, Map.of("data_scope", describer.promptBlock(scope)))`。
`agent-system.md` 增加 `{{data_scope}}` 占位符和回答规则。移除无 scope 的 `create(model)` 重载，测试显式传入范围，避免"忘记传范围导致无限制"的默认值。

**替代方案**：新增 `get_data_scope` 工具——多一次工具往返，且模型可能不调用；提示词注入是确定性的，否决。

### D3：工具返回增加 `dataScope` 包装

`query_database` 返回：

```json
{
  "result": { "datasourceId": "...", "sql": "...", "explanation": "...", "result": {...}, "chart": {...} },
  "dataScope": { "restricted": true, "role": "student", "summary": "学生（仅本人：学号 2023002，班级 1）" }
}
```

用新的 `ScopedQueryResult` 记录包装，不改 `Text2SqlResult`（它是非 Agent API 的响应类型，加字段会波及前端契约）。

**替代方案**：把 `scope` 塞进 `Text2SqlResult`——污染公共响应；否决。把范围只放在提示词里——模型在组织答案时缺少"本次结果的范围"锚点；否决。

### D4：确定性回答兜底（追加式）

新增 `ScopeAnswerGuard`：给定 `scope` 与最终回答文本，当 `restricted` 且回答匹配否定断言（`不存在`、`没有数据`、`数据库中只有`、`未找到`、`没有找到`、`无数据`），且回答尚未包含范围说明时，返回一段标准澄清：

> （说明：当前账号的数据范围仅限{summary}；未返回的数据可能在该范围之外，不能据此判断数据库中不存在。）

控制器在 Agent 流中累积 `delta` 文本，在 `done` 之前若守卫触发，再补发一个 `delta` 事件（append-only）。这样保留流式体验，也不改动前端契约。

**替代方案**：整段缓冲后再发——丢失流式；在前端加提示——文本仍然错误且不可强制；否决。

### D5：不探测范围外存在性

提示词明确禁止为判断范围外数据是否存在而发起 count/存在性查询；工具层不做额外探测。即使模型违规，行级过滤也只会返回 0 行，守卫会把"不存在"改写为范围说明。

### D6：范围描述不泄露

`promptBlock`/`summary` 只包含调用者自身信息：

- 学生：本人学号、所属班级 **id**（沿用现有 `callerContext` 的表述）。
- 辅导员：其管理班级 id 列表。
- 管理员：`全量数据，不受范围限制`。

不列举范围外表名、班级名称或其他学号；表/列可见性仍由 `TablePolicy` 裁剪。

## Risks / Trade-offs

- [模型仍可能换一种说法断言缺失] → 守卫覆盖常见否定词；提示词明确禁止；后续可扩充词表（不改契约）。
- [守卫误报，追加多余说明] → 只在 `restricted` 且出现否定断言时触发；管理员与已含范围说明的回答不追加。
- [流式回答末尾突然多一段] → 文案固定、以"（说明：…）"开头，视觉上是脚注；比错误结论更可接受。
- [提示词变长] → 每个请求约增加 1–2 行 token，可忽略。
- [Agent 会话跨轮次] → 系统提示词按请求重建，范围不会沿用上一个用户；AgentScope 会话按用户隔离。
- [工具 JSON 结构变化影响既有测试/子智能体] → 更新 `SqlToolsTest` 与提示词说明；`sql_runner` 子智能体读 `result` 字段，需在提示词中说明新信封。

## Migration Plan

- 纯后端改动，无数据库/配置迁移；随镜像发布即生效。
- 回滚：还原 `HarnessAgentFactory` 与 `SqlTools` 的返回结构、移除守卫即可；权限执行不受影响。
- 线上验证：学生账号问"1 班和 2 班数学平均分"，回答必须说明范围限制且不出现"2 班不存在"；管理员同样问题不追加免责声明。

## Open Questions

无。范围外存在性探测已明确禁止，不需要在实现阶段再决策。
