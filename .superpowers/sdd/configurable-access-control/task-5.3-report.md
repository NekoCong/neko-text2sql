# Task 5.3 implementer report

Status: DONE. Full backend suite: 306 tests, 0 failures, BUILD SUCCESS (no DASHSCOPE_API_KEY).

## Changes

- `AccessAdminRepository`/`R2dbcAccessAdminRepository`: `dataScopesOf`, `dataScope` (by unique key), `dataScopeById`, `upsertDataScope`, `deleteDataScope`; bound SQL; upsert is `INSERT ... ON DUPLICATE KEY UPDATE` on `(role_id, datasource_id, table_name)` preserving id/created_at; row mapper fails closed to `DENY` on unknown stored policy.
- `DataScopeService` (new): list/upsert/delete; shape checks 400; role 404; datasource 404; table/column/attribute validated against live metadata/principal catalog → 422; introspection on the blocking scheduler; SUCCESS audit rows share the mutation transaction; DENIED rows use REQUIRES_NEW.
- DTOs `DataScopeRequest`, `DataScopeView`.
- `AccessControlController`: three endpoints under `/data-scopes` with the local `{status,message}` handler.
- `AccessControlDataScopeTest` (12): empty list, FILTER upsert, re-upsert keeps id + policy switch, shape 400s incl. raw SQL, 422 unknown table/column/attribute, 404 role/datasource, student 403, DELETE 204/404, audit, next-request effect.

## Verification

- `mvn test` (no key) → 306 tests, 0 failures, BUILD SUCCESS.

## Concerns

- Unknown attribute is an audited 422; shape violations are 400 and unaudited.
- Adding repository methods required updates to two anonymous test implementations.
- `ON DUPLICATE KEY UPDATE` relies on MySQL/H2 MySQL mode.
- Upsert re-reads after write; a concurrent delete between them would 500 (fail closed).
