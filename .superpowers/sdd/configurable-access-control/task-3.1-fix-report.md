# Task 3.1 — Fix Report (red suite → green)

Scope: make the deterministic pipeline enforce DB-configured data policies and get the whole
`mvn test` suite green **without** `DASHSCOPE_API_KEY`.

Nothing in the protected set changed: `SecurityConfig` route permissions, CTE handling and the
`app-schema.sql` DDL are untouched, and the access repository gained only the two new methods.

## Root causes fixed

| # | Root cause | Fix |
|---|-----------|-----|
| 1 | `DataScopeResolver` projected a scope but never validated it, so an account without `user_scope` rows (or without a data policy) silently got a half-usable scope. | `resolve(userId, role, tokenVersion)` now chains `.map(DataScope::from).map(DataScopeResolver::requireUsable)`. Bypass passes; `access == null` or `dataScopes().isEmpty()` denies; every `FILTER` rule must have its principal value (`CLASS_IDS` non-empty, `STUDENT_ID` non-null, `STUDENT_NO`/`USER_ID`/`USERNAME` non-blank, unknown attribute denies). Denial is `403 未配置数据范围，请联系管理员`. |
| 2 | The H2 seed had roles and datasource grants but no `access_data_scope` rows, so a scoped test user could not resolve. | `src/test/resources/db/test-access-data.sql` now seeds the 10 student/counselor policy rows (ids `test-scope-<role>-<table>`), idempotently (`INSERT … SELECT … WHERE NOT EXISTS`), mirroring `db/app-data.sql`. |
| 3 | The legacy admin endpoint wrote only `datasource_access`, while enforcement reads `access_role_datasource`; revoking did nothing to the next request. | `AccessControlRepository.grantDatasourceByCode` / `revokeDatasourceByCode` (+ `R2dbcAccessControlRepository` impl, exact SQL from the brief). `DatasourceAccessService` injects `AccessControlRepository` and chains the new call with `.then()` after the legacy `grant`/`revoke`. |
| 4 | `Text2SqlControllerPersistenceTest` created a user with only `user_scope` rows — no `app_user` row, no role — so the new resolver chain rejected it (`NoSuchElementException` / 401). | The test now inserts an `app_user` row for the generated id and assigns the `student` role via `AccessControlRepository.assignRoleByCode` (the JWT already carries `ver=0`). |
| 5 | Suite was red for the above. | Full run without `DASHSCOPE_API_KEY`: all model-dependent paths are already stubbed (pipeline stub / `ModelResolver` stubs) — no new stubbing was needed. |

## Tests added

`DataScopeResolverTest` (6 new cases, 16 total):

- `anAccountWithoutDataScopeIsRejected` — student with no `user_scope` → 403.
- `anAccountWhoseRoleHasNoDataPoliciesIsRejected` — role with zero `access_data_scope` rows → 403.
- `aCounselorWithoutManagedClassesIsRejected` — no `class_id` → 403.
- `aStudentMissingStudentIdIsRejected` / `aStudentMissingStudentNoIsRejected` — partial principal → 403.
- `anAdminWithoutDataScopeStillResolves` — the bypass short-circuit still works.

Each asserts `FORBIDDEN` **and** the `未配置数据范围` reason. `cleanUp` also removes the custom
`scope-test-*` role (join row deleted first so the FK-less `role_id IN (…)` lookup still matches).

## Commands and output

From `E:\workspace-java\text2sql-demo\text2sql-backend`.

### Compile

```powershell
$env:DASHSCOPE_API_KEY=$null; mvn -o test-compile
```

```
[INFO] Building text2sql-backend 0.0.1-SNAPSHOT
[INFO] BUILD SUCCESS
```

### Covering tests

```powershell
$env:DASHSCOPE_API_KEY=$null; mvn -o test -Dtest='DataScopeResolverTest,Text2SqlControllerTest,Text2SqlControllerPersistenceTest,DatasourceAccessControllerTest'
```

```
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 -- in com.nekocong.text2sql.security.DataScopeResolverTest
[INFO] Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -- in com.nekocong.text2sql.web.DatasourceAccessControllerTest
[INFO] Tests run:  1, Failures: 0, Errors: 0, Skipped: 0 -- in com.nekocong.text2sql.web.Text2SqlControllerPersistenceTest
[INFO] Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -- in com.nekocong.text2sql.web.Text2SqlControllerTest
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Full suite (no API key)

```powershell
$env:DASHSCOPE_API_KEY=$null; mvn -o test
```

```
[INFO] Tests run: 227, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

(Baseline before this task was 192 tests; the delta is the new policy/scope tests added by the task
itself.)

## Notes / concerns

- `DatasourceAccessService` now performs two independent statements per call (legacy table, then the
  access-control table). They are chained with `.then()` rather than zipped because both are
  idempotent; a failure in the second leaves the first applied. Task 5.5 replaces the legacy matrix,
  at which point the legacy write can be dropped.
- `DataScopeResolver` denies when a non-bypass account has **no** policy at all. Any future role must
  therefore be seeded with at least one `access_data_scope` row to be usable — intentional fail-closed.
- The H2 database is shared across Spring contexts in one Maven run, so the new seed rows and the new
  test rows stay idempotent / prefix-scoped.

---

# Task 3.1 — Review Round 1 Fixes (Needs fixes → green)

Addressed one Critical, three Important and the selected minors from the task-3.1 review.

Protected set untouched: `SecurityConfig` route permissions, CTE handling, `app-schema.sql`.

## C1 — Four endpoints 401'd unconditionally (missed 2-arg caller migration)

`KnowledgeCitationController` (`preview-url`, `file`, `content`) and `KnowledgeRetrievalController`
(`search`, `search/debug`) called `scopes.resolve(subject, role)`; the 2-arg overload delegated with
`null` and `AccessContextResolver` rejects a null `ver` with 401, so all four were dead.

- All four sites now pass `TokenClaims.tokenVersion(jwt)`.
- The 2-arg `DataScopeResolver.resolve` overload is **deleted**, so the compiler forces every current
  and future caller to pass the version. `DataScopeResolverTest` now calls `resolve(..., null)`
  explicitly, with a comment recording why.

## I1 — Rewriter guessed the datasource and failed closed for multi-grant roles

- `Text2SqlPipelineService.enforce(sql, datasourceId, scope)`; both call sites (`toEvents`,
  `execute`) pass the already-resolved `id`.
- `DataScopeRewriter.rewrite(sql, scope, datasourceId)` — no 2-arg overload was kept; the id is
  threaded through every recursive helper down to `policy.predicates(scope, datasourceId, name)`.
- `DataScopePolicyEngine.decide` checks `access.bypassesDataScope()` **before** the null-datasource
  check, so a bypassing caller stays unrestricted on an unknown datasource.
- `TablePolicy.datasourceId` remains only as the single-grant compatibility fallback; `decide`
  treats an explicitly supplied id as authoritative.
- New tests: `usesTheExplicitDatasourceIdForACallerWithSeveralGrants`,
  `theExplicitDatasourceIdSelectsThatDatasourcesPolicies`, `anAdminIsUnrestrictedEvenOnAnUnknownDatasource`.

## I2 — Revoke dual-write could fail open

- `revoke`: enforcement row deleted **first**, then the legacy row — a mid-way failure can only leave
  enforcement narrower than the matrix. `grant` keeps the legacy-first order (failure leaves
  enforcement narrower too). Both orders are documented on the methods.
- `grantDatasourceByCode` zero-row behaviour made consistent with its javadoc: a repeat grant
  legitimately updates 0 rows, so role existence is checked separately by a new `requireRole`
  helper that raises "角色不存在：" for an unknown/disabled code (mirrors `assignRoleByCode`).

## I3 — `scopedExplanation` branched on the role name

Now labelled from the effective `FILTER` attributes: `STUDENT_NO`/`STUDENT_ID` → "仅本人";
`CLASS_IDS` → "所管班级"; otherwise "当前数据范围". Seeded-role strings are unchanged.

`scopedExplanation` is package-private for the new unit test
`theScopeNoteIsLabelledByTheEffectiveFilterNotTheRoleName` (student / counselor / username-only).

## Minors

- `AccessStatusWebFilter` reuses `TokenClaims.tokenVersion`; the private duplicate parser is gone.
- `DataScope.admin(userId)` removed (unused; its scope asserted `isAdmin()` which it did not grant).
  `isStudent()` is kept — two tests still assert it.
- `TestAuth.currentUserId` restored `expectStatus().isOk()` and no longer fabricates a user id.
- `DataScopeRewriter.quote` escapes backslashes before quotes; `quote(...)` made package-private and
  covered directly (`escapesBackslashesAsWellAsQuotes`), because JSQLParser rejects the escaped
  sequence when re-parsing a derived-table predicate.
- Formatting: joined line in `Text2SqlPipelineServiceTest:267`, double blank line in
  `KnowledgeSearchServiceTest`, long lines in `Text2SqlPipelineService.callerContext` and
  `DataScopeResolver.resolve`, unused `table` parameter dropped from `renderPredicates`.
- `AccessRepository.scopes/datasourcesForRole` marked transitional with `TODO(task 5.5)` — not removed.

## Commands and output

From `E:\workspace-java\text2sql-demo\text2sql-backend`.

```powershell
$env:DASHSCOPE_API_KEY=$null; mvn -o test-compile
```
```
[INFO] BUILD SUCCESS
```

```powershell
$env:DASHSCOPE_API_KEY=$null; mvn -o test -Dtest='DataScopeResolverTest,DataScopeRewriterTest,Text2SqlPolicyEnforcementTest,Text2SqlPipelineServiceTest,KnowledgeSearchServiceTest,TablePolicyTest,DataPermissionIntegrationTest,DatasourceAccessControllerTest,Text2SqlControllerTest,Text2SqlControllerPersistenceTest'
```
```
[INFO] Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -- KnowledgeSearchServiceTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 -- DataScopeResolverTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- TablePolicyTest
[INFO] Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -- Text2SqlPipelineServiceTest
[INFO] Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -- Text2SqlPolicyEnforcementTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- DataPermissionIntegrationTest
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0 -- DataScopeRewriterTest
[INFO] Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -- DatasourceAccessControllerTest
[INFO] Tests run:  1, Failures: 0, Errors: 0, Skipped: 0 -- Text2SqlControllerPersistenceTest
[INFO] Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -- Text2SqlControllerTest
[INFO] Tests run: 97, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

```powershell
$env:DASHSCOPE_API_KEY=$null; mvn -o test
```
```
[INFO] Tests run: 232, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Concerns

- **No automated test covers the four knowledge endpoints end-to-end.** They are compile-verified
  only: there is no `WebTestClient` test hitting `/api/text2sql/citations/**` or
  `/api/text2sql/knowledge/search`, and the knowledge module is `@ConditionalOnProperty`-gated.
  C1 is fixed and the compiler now prevents the 2-arg form from returning, but a regression test
  would need a knowledge-enabled test context.
- The `grant`/`revoke` two-statement sequences are still not transactional; ordering makes the
  failure modes safe-by-direction rather than atomic. Task 5.5 removes the legacy write entirely.
