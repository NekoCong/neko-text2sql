# Task 4.1–4.3 implementer report

Status: DONE. Full backend suite green: 247 tests, 0 failures, BUILD SUCCESS (no DASHSCOPE_API_KEY).

## Changes

- `sql/DataScopeRewriter`:
  - CTE visibility now follows SQL semantics: each CTE body is rewritten with only the preceding CTEs visible, then the current name is added. A CTE can no longer shadow a protected table inside its own body.
  - `WITH RECURSIVE` rejected with a readable `SqlPermissionException`.
  - A CTE whose unqualified, lower-cased name is in the caller's visible (non-denied) table set is rejected regardless of body.
  - `cteName` normalizes/unqualifies/strips quotes; nameless CTEs denied.
  - `inheritedVisibleTables` wraps `TablePolicy.visibleTables`; admin null set → empty.
  - `renderPredicate` documents the bare-identifier + validated-literal rule.
- `DataScopeRewriterTest`: 9 new tests (41 total): same-name bypass rejected, benign-body collision rejected, case-insensitive collision, legal CTE body filtered, denied table body rejected, schema-qualified body still filtered, nested CTE earlier-name visibility, self-reference denied, recursive rejected, unsafe predicate column rejected, restricted CTE query has no out-of-scope rows.

## Verification

- `mvn test` (no key) → 247 tests, 0 failures, BUILD SUCCESS.
- `mvn test -Dtest=DataScopeRewriterTest` → 41 tests, 0 failures.

## Concerns

- Collision check uses the caller's *visible* (non-DENY) table set, not every configured table. A DENY-only table can be shadowed by a CTE name; access is still closed because the outer reference to the physical table would be denied and the CTE body is filtered. Strict "protected = every configured table" would need `TablePolicy` to expose configured names (not changed per the no-touch constraint).
- Predicate hardening was largely present from 3.1; only tests were added.
- The admin (bypass) path returns SQL unchanged and deliberately skips the CTE collision check.
