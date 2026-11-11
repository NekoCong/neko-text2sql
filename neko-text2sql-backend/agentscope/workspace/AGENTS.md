# Text2SqlAgent 工作区

本目录是 AgentScope 的运行时工作区。

## 说明

- Agent 的**人格与行为规则**由提示词文件统一管理：`classpath:prompts/agent-system.md`
  （由 `text2sql.agent.sys-prompt-file` 指定，见 `resources/prompts/README.md`）。
- 数据库结构的**事实来源是实时内省**（`SchemaIntrospector`）；本工作区 `knowledge/` 下的文档仅供参考。
- 仅执行只读 `SELECT` / `WITH` 查询：连接只读、限行且带超时。
- 所有回复使用**简体中文**。

## 知识库

- `knowledge/SCHEMA.md` — 演示库结构文档（非事实来源，仅供阅读）。
