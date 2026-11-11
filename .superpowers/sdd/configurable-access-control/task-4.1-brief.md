# Task 4.1–4.3 brief — CTE-safe rewriting and predicate hardening

Goal: close the CTE bypass in `DataScopeRewriter` and harden the row-predicate builder.

## Current behaviour / defect

`DataScopeRewriter` collects every CTE name before rewriting and skips table filtering for any name
in that set. A CTE named like a protected table (`WITH student AS (SELECT * FROM student) SELECT * FROM student`)
therefore skips the whitelist and row filter. Recursive CTEs are not explicitly rejected.

## Required changes

1. CTE scoping in `sql/DataScopeRewriter`:
   - A non-recursive CTE's **own name is not visible inside its body**; only CTEs defined earlier in
     the same `WITH` list are visible. Do not add the current CTE name before rewriting its body.
   - Reject any CTE whose name (case-insensitive, unqualified) collides with a protected table name,
     even if the body looks benign. Fail closed with `SqlPermissionException`.
   - Reject `WITH RECURSIVE` in this phase with a readable reason.
   - Keep the existing “parse failure → reject” behavior and the current table/predicate enforcement
     for every physical table reference inside CTE bodies.
2. Harden the predicate builder (verify what 3.1 already did):
   - identifiers (`table`, `column`) must match `[A-Za-z_][A-Za-z0-9_]*`; otherwise reject;
   - string values (studentNo/username/userId) must escape `'` and `\`; numeric values must be
     numbers; empty/missing principal values deny;
   - no raw predicate text from the model is ever interpolated.
3. Tests in `DataScopeRewriterTest` (add to the existing suite):
   - `WITH student AS (SELECT * FROM student) SELECT * FROM student` → rejected (collision), not
     bypassed;
   - a legal CTE that references a protected table in its body → the body is still filtered;
   - nested CTEs where an inner CTE references an earlier CTE → allowed, earlier names visible,
     current name not visible;
   - a schema-qualified CTE body (`FROM campus.student`) → still filtered/denied;
   - `WITH RECURSIVE` → rejected with a readable reason;
   - quote/backslash handling and unsafe identifier rejection (if not already covered by 3.1);
   - a restricted user's CTE query returns no out-of-scope rows (task 4.3 regression).
4. Do not change `SecurityConfig`, controllers, the access repository/schema, or the policy engine.

## Constraints

- Java 25; no new dependencies (JSqlParser is already present).
- Fail closed; never execute an unfiltered statement.
- Full `mvn test` without `DASHSCOPE_API_KEY`; report BUILD SUCCESS + count. Commit.
