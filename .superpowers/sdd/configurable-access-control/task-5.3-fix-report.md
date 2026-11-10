# Task 5.3 fix report — data-scope policy admin API

Review outcome addressed: **Needs fixes** (1 Important + minors). All items below are in commit
on `master`; covering class `AccessControlDataScopeTest` is now 20 tests.

## Important

1. **Identifier / raw-SQL rejection is now 422 and audited as DENIED.**
   - `requireShape` no longer checks the table/column *syntax*; it only rejects structurally
     malformed requests (missing body/fields, bad policy, FILTER without column+attribute,
     ALL/DENY carrying one) with 400 and no audit row.
   - The bare-identifier rule now lives in `checkIdentifiers`, which runs inside the blocking
     metadata pass and returns a `Denial(422, "标识符不合法：<value>")`; it is persisted as a
     DENIED `data_scope.upsert` row through the existing `REQUIRES_NEW` denial path.
   - Unknown attribute (`未知主体属性：<value>`) moved to the same pass, so it is a 422 + DENIED
     too (previously it was audited from a separate branch; behaviour unchanged, code unified).
   - `AccessControlDataScopeTest.rawSqlOrQualifiedOrQuotedNamesAreUnprocessableAndAudited`
     asserts 422 for `score WHERE 1=1`, `campus.score`, `"score"`, `student_id = 1 OR 1=1`,
     `student-id`, and asserts exactly 5 DENIED rows, 0 SUCCESS, each reason prefixed
     `标识符不合法`.

## Minors

- **Canonical table/column names**: `inspect` stores `TableSchema.name()` and
  `ColumnSchema.name()`, and the attribute is stored as `PrincipalAttribute.code()`. Covered by
  `aCaseVariantReUpsertKeepsTheRowAndStoresCanonicalNames` (`SCORE`/`Score`, `STUDENT_ID` →
  single row, canonical spelling).
- **No re-resolution in `findTable`**: `requireDatasource` returns the resolved `DataSource`;
  `findTable(DataSource, …)` uses it directly. A registry removal after validation therefore
  fails as an introspection error rather than a second 404/500 ambiguity.
- **Metadata validated outside the transaction**: `upsert` validates (role, datasource,
  attribute, table, column, type) first, then `store(...)` wraps only the upsert + SUCCESS audit
  in `transactions::transactional`. `delete` likewise loads the row outside and wraps only
  `deleteAndAudit` (delete + SUCCESS audit). No app-DB connection is held across JDBC metadata
  work.
- **Error-shape symmetry**: controller takes `@RequestBody(required = false)` and
  `@RequestParam(required = false) String roleId`, returning a `ResponseStatusException` so the
  local handler renders `{status,message}`. Covered by
  `aMissingBodyOrRequiredParameterKeepsTheStatusMessageShape` (empty body → `请求体不能为空`;
  `{}` and missing/empty `roleId` → `角色标识不能为空`).
- **Unknown stored policy**: `anUnknownStoredPolicyIsListedAsDeny` inserts `policy='BOGUS'` and
  asserts list maps it to `DENY` and the policy engine denies the table (repository
  `parsePolicy` already failed closed).
- **Dead branch removed**: the old `resolveAttribute(…).then(Mono.empty())` chain is gone; the
  attribute is resolved (or rejected) inside `inspect`.
- **Datasource id pattern relaxed**: `DATASOURCE_ID_PATTERN = [A-Za-z0-9._-]{1,64}`, matching the
  registry's id space (ids are only bound, never interpolated; `DataSourceDefinition.setId` does
  not constrain the form). Documented on the constant. Covered by
  `aDatasourceIdWithRegistryCharactersIsAccepted` (`no-such-ds` → 404, not 400).
- **Type compatibility**: documented on `DataScopeRequest`. `studentId` (rendered as a bare
  number) must target a numeric column; a known text type (`VARCHAR`/`CHAR`/`TEXT`/`CLOB`/…,
  including H2's `CHARACTER VARYING`) is rejected with 422 `列类型与主体属性不匹配：…`.
  `classIds` is an `IN` list and `userId`/`username`/`studentNo` compare as text, so all three
  accept any column. Unknown type names are not rejected (left to the policy engine). Covered by
  `aNumericAttributeOnATextColumnIsUnprocessableAndAudited` (rejects `student.name`, accepts
  `student.id` and `student.student_no`).
- **Test gaps**: `reUpsertPreservesTheCreatedAtTimestamp`, `aCaseVariantReUpsert…`,
  `listIsOrderedByDatasourceThenTable` (ordered `class`, `score`, `student`; datasource filter),
  `aMissingBodyOrRequiredParameterKeepsTheStatusMessageShape`,
  `aDatasourceIdWithRegistryCharactersIsAccepted`, `anUnknownStoredPolicyIsListedAsDeny`.

## Validation

- `mvn -o test -Dtest=AccessControlDataScopeTest` — 20 run, 0 failures.
- `mvn -o test` (no `DASHSCOPE_API_KEY`) — **BUILD SUCCESS**, Tests run: **314**, Failures: 0,
  Errors: 0, Skipped: 0.

## Notes / residual concerns

- The type check is deliberately narrow: only a single-valued numeric attribute
  (`studentId`) on a *known* text column is rejected. Widening it (e.g. `userId` on a numeric
  column) risks false rejections on dialects that report unusual type names, so unknown types
  pass through to the policy engine.
- Identifier case is canonicalised at write time, but the unique key `(role_id, datasource_id,
  table_name)` is case-sensitive in MySQL's default collation on H2; a pre-existing row stored
  with a non-canonical spelling (only possible from a manual insert) is not normalised by this
  change.

## Fix round 2 (orchestrator, after the fixer session hit an upstream 429)

- equireDatasource now passes the resolved DataSource into equireTableAndColumnAndType/inspect; the registry is resolved once per upsert. Javadoc corrected (no reuse overclaim, no slash claim).
- Removed the dead Optional/empty fall-through: onErrorResume returns deny(...).then(Mono.<DataSource>empty()) with a comment that deny always errors.
- inspect no longer carries an unreachable unknown-attribute branch (validated in checkIdentifiers).
- GET ?datasourceId= uses the relaxed datasource-id pattern (same as PUT).
- Multi-datasource ordering test: added a second seeded test datasource erp-prod (dash in id; seeded with the demo schema) and asserted full (datasource_id, table_name) ordering plus the relaxed query filter.
- Full suite: 315 tests, 0 failures, BUILD SUCCESS (no DASHSCOPE_API_KEY).
