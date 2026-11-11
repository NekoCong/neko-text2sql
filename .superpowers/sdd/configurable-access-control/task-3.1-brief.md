# Task 3.1 brief — policy-driven enforcement chokepoint

Goal: make the deterministic pipeline, the agent tools and the schema providers enforce the
database-configured data policies instead of `TablePolicy`'s role-name branches.

## Context

- `com.nekocong.text2sql.access` already provides `EffectiveAccess`, `DataScopePolicyEngine`
  (`decide(access, datasourceId, table)` → `PolicyDecision` with `ALL`/`DENY`/`FILTER` and
  `RowPredicate` list), `AccessContextResolver.resolve(userId, tokenVersion)` and the R2DBC
  repository. 190+ tests are green.
- `security/DataScope` still carries the legacy identity and is used across the pipeline.
- `security/TablePolicy` still hardcodes `CAMPUS_TABLES`, per-role row filters and `roles`.

## Required changes

1. Extend `DataScope` with a seventh component `EffectiveAccess access` (nullable). Keep source
   compatibility with a 6-arg constructor that passes `null`, and add
   `static DataScope from(EffectiveAccess)` (role = first effective role code, classIds/studentId/
   studentNo/datasources from the principal, access included). Add `access()` and
   `bypassesDataScope()` (delegates to access, false when null).
2. `DataScopeResolver`: add `resolve(String userId, String role, Integer tokenVersion)` that
   delegates to `AccessContextResolver.resolve(userId, tokenVersion).map(DataScope::from)`.
   Keep the old 2-arg method for tests only if it delegates with `null` (which fails closed).
   Update every production caller to pass the JWT `ver` claim (helper: read `jwt.getClaim("ver")`
   as `Integer`; null → fail closed). Do not change the filter.
3. Replace `TablePolicy`'s role maps with the policy engine. New API (keep the class name/package):
   - `boolean allows(DataScope scope, String datasourceId, String table)` — `DENY` → false,
     `ALL`/`FILTER` → true, null access → deny (unless legacy admin? no: use access).
   - `Set<String> visibleTables(DataScope scope, String datasourceId)` — null/empty means
     unrestricted when `scope.bypassesDataScope()`, otherwise the distinct table names whose
     decision is not `DENY` (from `scope.access().dataScopes()`).
   - `List<RowPredicate> predicates(DataScope scope, String datasourceId, String table)` — the
     `FILTER` predicates, empty for `ALL`, throw/deny for `DENY`.
   Remove `tablesFor(role)`, `filterFor(scope, table)` and the hardcoded maps once all callers are
   migrated.
4. `DataScopeRewriter`: for each table reference, call `predicates(...)` and render the OR of
   `RowPredicate`s. Values come from the `DataScope`: `classIds` → numeric list, `studentId` →
   number, `studentNo`/`username`/`userId` → SQL-quoted string. A missing/empty value or a denied
   table → `SqlPermissionException` before execution. Keep the existing identifier quoting and the
   CTE behavior untouched (task 4.1 owns CTE changes).
5. Migrate callers:
   - `FullSchemaProvider` / `RagSchemaProvider`: filter tables with `allows(scope, datasourceId, table)`.
   - `SqlTools.describeTables`: same.
   - `KnowledgeSearchService`: replace `tablesFor(scope.role())` with
     `visibleTables(scope, datasourceId)`; keep the `VectorFilter.allowedTables` contract
     (null = unrestricted).
   - `Text2SqlPipelineService.scopedExplanation` / `callerContext`: derive the wording from
     `scope.access()` rather than `isAdmin()/isStudent()/isCounselor()`. Admin = bypass; otherwise
     use the effective role display names and the same "结果已按当前账号的数据范围…过滤" sentence
     so existing user-visible behavior is preserved for the seeded roles.
6. Tests: update `DataScopeRewriterTest`, `SchemaTrimmingTest`, `Text2SqlPipelineServiceTest`,
   `SqlToolsTest`, `KnowledgeSearchServiceTest`, `RagSchemaProviderTest`, `FullSchemaProvider`
   tests as needed; add a test with a custom role (no `student/counselor` name) whose DB policy
   filters a table, proving enforcement no longer branches on the role name; add a deny case that
   asserts no SQL is executed.
7. Do not touch `SecurityConfig` route permissions or controller `Roles.isAdmin` checks — task 3.4
   owns them. Do not change `AccessContextResolver`, the repository, or the schema.

## Constraints

- Java 25 / Spring Boot 4.1; no new dependencies.
- Fail closed: missing access, missing principal value, unknown policy, or unsafe identifiers deny.
- Keep the pipeline/agent behavior identical for the seeded `admin/student/counselor` roles.
- Run `mvn test`; report `BUILD SUCCESS` + count. Commit with a conventional message.
