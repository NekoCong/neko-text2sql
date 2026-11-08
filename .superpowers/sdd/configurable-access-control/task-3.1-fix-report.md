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
