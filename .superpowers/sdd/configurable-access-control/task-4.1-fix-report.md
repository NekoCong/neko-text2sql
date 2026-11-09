# Task 4.1–4.3 fix report (review round 1: "Needs fixes")

## Verdict
All review findings addressed: C1 (Critical), I2 and I3 (Important), and the selected minors
(quoted CTE names, duplicate recursive check, misleading test, missing tests). Full suite green.

## Defects reproduced before fixing
A throwaway probe confirmed each report against the built code:
- C1: `WITH salary AS (SELECT 1 AS x) SELECT * FROM campus.salary` returned the statement
  **unmodified** (physical table unfiltered, whitelist skipped).
- I3: username `a\'b` produced
  `SELECT * FROM (SELECT * FROM student WHERE username) AS student` — the predicate degraded to a
  bare column, i.e. **every row** (widening).
- Minor: ``WITH `mine` AS (...) SELECT * FROM `mine` `` was rejected with `表 \`mine\` 不在当前数据范围内`.

## Fixes

### C1 — schema-qualified reference must not match a CTE by base name
`rewriteTable` now treats a reference as a CTE only when it is **unqualified**:
`isUnqualified(table)` checks `getSchemaName()` and `getDatabase().getDatabaseName()` (JSqlParser
4.9 reports a missing qualifier as `null` or an empty string, so both are treated as absent).
A qualified reference always goes through `policy.predicates(...)`.
Files: `sql/DataScopeRewriter.java`.

### I2 — collision set is policy-configured, not caller-visible
Added `TablePolicy.configuredTables(scope, datasourceId)` returning every table name configured for
the datasource (ALL, FILTER and DENY), empty for a bypassing/unresolved caller. The collision check
now uses it (`protectedTables(...)`); `visibleTables` is unchanged and still drives filtering.
Files: `security/TablePolicy.java`, `sql/DataScopeRewriter.java`.

### I3 — escaped values must not silently truncate the predicate
Kept the render-then-parse approach (see "Why not AST injection" below) but added
`verifyPredicate`: the parsed tree must be one `IN` over a plain column per expected predicate with
the expected operand count, otherwise the query is **denied**. Values that JSqlParser cannot read back
verbatim (any value containing `\'`) now fail closed instead of dropping the filter; safe values still
render as before.
Files: `sql/DataScopeRewriter.java`.

**Why not AST injection (`StringValue`/`LongValue`):** verified in a probe that JSqlParser 4.9's
`InExpression.toString()` emits `username IN 'a'b'` and `class_id IN 1, 2` — missing parentheses and
unescaped quotes. Injecting those nodes would have produced invalid SQL, so the rendered string
(with `''`/`\\` escaping) is retained and guarded by verification.

### Minors
- Quoted CTE names/references normalized: `normalizeIdentifier` strips `` ` ``, `"`, `[`/`]`;
  `rewriteTable` dequotes the name before the CTE lookup and before consulting the policy.
- Duplicate top-level `WITH RECURSIVE` pre-check removed; the WITH loop covers it (top-level and
  nested).
- Misleading `aCteNameIsNotVisibleInsideItsOwnBody` removed; `earlierCtesAreVisibleInsideALaterCteBodyButTheCurrentNameIsNot`
  and `aCteCannotReferenceItsOwnNameAsAPhysicalTable` remain.

## Tests
`DataScopeRewriterTest`: 41 → **49** (8 net new, 6 original tests unchanged in intent).

| Test | Finding |
| --- | --- |
| `aSchemaQualifiedReferenceIsNotShadowedByACte` | C1 |
| `aSchemaQualifiedProtectedTableInACteBodyIsStillFiltered` | C1 body |
| `rejectsACteShadowingADeniedTable` | I2 |
| `predicateValuesContainingQuoteAndBackslashNeverDegradeToABareColumn` | I3 |
| `aBackslashOnlyValueKeepsTheEscapedLiteral` | I3 positive case |
| `handlesQuotedCteNamesAndReferences` | minor |
| `rejectsACteWhoseQuotedNameCollidesWithAProtectedTable` | minor |
| `rejectsNestedRecursiveCtes` | minor |
| `rejectsACteNameCollisionInsideANestedWithList` | minor |

Retained from round 1: same-name CTE bypass, benign-body collision, case-insensitive collision,
legal CTE body filtered, denied table in CTE body, schema-qualified CTE body filtered, nested CTEs,
self-reference denied, `WITH RECURSIVE`, unsafe predicate column, restricted user's CTE query, plus
the 3.1 quote/backslash unit assertions.

## Commands and output

```
> mvn test -Dtest=DataScopeRewriterTest
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

> mvn test
[INFO] Tests run: 255, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

(`DASHSCOPE_API_KEY` unset for both runs; run from `text2sql-backend`.)

## Notes / residual
- Values containing the two-character sequence `\'` are now denied rather than filtered. This is a
  fail-closed improvement over the previous silent widening, but it means such a username cannot run
  policy-filtered queries until the parser round-trip is replaced (a future change could build the
  predicate with a parser whose `toString` round-trips literals safely).
- The collision rule intentionally applies to DENY-configured tables too; a caller whose only rule
  for a name is DENY can no longer use that name as a CTE alias.
