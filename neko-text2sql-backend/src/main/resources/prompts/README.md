# 提示词目录（Prompt Catalog）

集中管理所有 LLM 提示词模板，避免散落在 `application.yaml` 或 Java 代码里。

## 文件

| 文件 | 用途 | 占位符 |
|------|------|--------|
| `agent-system.md` | AgentScope Agent 的系统提示词（人格 + 调用者数据范围） | `{{data_scope}}` |
| `nl2sql-system.md` | 确定性 NL2SQL 流水线的系统提示词 | `{{dialect}}` |
| `nl2sql-user.md` | NL2SQL 用户提示词（注入 schema 与问题） | `{{dialect}}` `{{schema}}` `{{question}}` |

## 约定

- 占位符写法：`{{name}}`，由 `prompt.PromptCatalog#render(location, vars)` 替换。
- 加载路径基于 classpath（如 `prompts/nl2sql-user.md`）。
- `application.yaml` 仅保留提示词**文件路径**（`text2sql.agent.sys-prompt-file`），不再内联长文本。
- 需要覆盖时，改文件即可（或通过 `text2sql.agent.sys-prompt` 内联覆盖 Agent 人格）。
