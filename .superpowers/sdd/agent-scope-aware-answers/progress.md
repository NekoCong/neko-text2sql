# agent-scope-aware-answers — progress

- Implemented 1.1–3.2 in `1ae6a4e`: `DataScopeDescriber` (pipeline `callerContext` wording moved verbatim), per-request `{{data_scope}}` rendering in `HarnessAgentFactory.create(Model, DataScope)` (no-scope overload removed), `ScopedQueryResult` envelope on `query_database`, `ScopeAnswerGuard` appending one clarification delta before `done`.
- Unit tests added: `DataScopeDescriberTest`, `ScopeAnswerGuardTest`, `HarnessAgentFactoryTest`, `Text2SqlControllerAgentStreamTest`; extended `SqlToolsTest`, `PromptCatalogTest`, `HarnessAgentFactorySubagentsTest`, `Text2SqlPipelineServiceTest`.
- 4.1 docs in `8d6914f`: `docs/nl2sql.md` §3/§5/§6.1/§10 and `README.md` (SSE delta clarification + agent scope awareness).
- 4.2 `mvn test` (with `DASHSCOPE_API_KEY` unset): `Tests run: 396, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`.
- Deployed to 47.108.66.221 at `8d6914f` via a git bundle (the server could not reach github.com at that moment); `deploy/bootstrap.sh` exit 0, all containers up, `/api/text2sql/health` = OK.
- Live verification (real model, real MySQL):
  - 4.3 student01 agent stream, "1 班和 2 班的数学平均分": answer reports only 1 班 and states "当前数据范围受限（仅限本人：学号 2023001，班级 1）…2 班的数据在当前范围内不可见"; no "2 班不存在"/"数据库只有 1 班"; no other student identities; `done` is the last event.
  - 4.4 student01 pipeline, same question: SQL `student_id IN (1)` / `student_no IN ('2023001')`; explanation keeps "（结果已按当前账号的数据范围（仅本人）过滤）" — unchanged from before the change.
  - 4.5 admin, same question in both modes: 1 班 90.75 / 2 班 79.25, unfiltered SQL, no scope disclaimer appended.
- 14/14 tasks checked; delta spec synced into `openspec/specs/data-permission/spec.md`; change archived to `openspec/changes/archive/2026-11-10-agent-scope-aware-answers`.
- `configurable-access-control` was also synced (new `openspec/specs/access-control/spec.md`) and archived to `openspec/changes/archive/2026-11-10-configurable-access-control`.
- Note: the pipeline prompt still renders the effective role *display name* from the access config (seeded roles store code = name, so `student：…`), because its wording had to stay byte-identical; the friendly `学生/辅导员` wording is in `summary`.
- Left open (unchanged, out of scope): `add-three-knowledge-bases` 10.2–10.5 (pgvector/MinIO integration tests and manual E2E/degradation checks).
