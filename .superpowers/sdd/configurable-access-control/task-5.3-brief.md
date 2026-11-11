# Task 5.3 brief — data-scope policy admin API

Goal: let an administrator configure per-role, per-datasource, per-table data policies (`ALL` /
`FILTER` / `DENY`) with server-side validation against live metadata and the closed principal
attribute catalog. No raw SQL input.

## API (base `/api/admin/access-control`, `access.manage`-gated)

- `GET /data-scopes?roleId=<id>[&datasourceId=<id>]` → `DataScopeView[]`, ordered by datasource,
  table. Unknown role → 404.
- `PUT /data-scopes` body `DataScopeRequest` → `DataScopeView` (upsert by role+datasource+table).
- `DELETE /data-scopes/{id}` → 204; unknown id → 404.

```json
// DataScopeRequest
{ "roleId": "...", "datasourceId": "campus", "tableName": "score",
  "policy": "FILTER", "columnName": "student_id", "principalAttribute": "studentId" }
// DataScopeView adds "id" and keeps the same fields (columnName/principalAttribute null for ALL/DENY)
```

Validation:
- role exists → 404 "角色不存在"; datasource exists in `DataSourceRegistry` → 404 "数据源不存在";
- `tableName` must exist in the datasource's live metadata → 422 "表不存在：<name>";
- `columnName` must exist in that table → 422 "列不存在：<name>";
- `policy` ∈ ALL/FILTER/DENY; FILTER requires `columnName` + `principalAttribute`, ALL/DENY must not
  carry either (400);
- `principalAttribute` must be in `PrincipalAttribute` (`userId`, `username`, `studentId`,
  `studentNo`, `classIds`) → 422 "未知主体属性：<value>";
- identifiers are bare `[A-Za-z_][A-Za-z0-9_]*`; reject anything else (422);
- upsert preserves the existing id; audit `data_scope.upsert` / `data_scope.delete` with before/after
  (SUCCESS and DENIED rows, reusing the 5.1/5.2 transaction pattern);
- the existing unique key `(role_id, datasource_id, table_name)` enforces one policy per table.

## Implementation

- Extend `access/AccessAdminRepository` + `R2dbcAccessAdminRepository` with data-scope
  list/find/upsert/delete; extend the admin services (prefer a new `DataScopeService`, like
  `RolePermissionService`, to keep files focused).
- Live metadata: `DataSourceRegistry.resolve(datasourceId)` + `SchemaIntrospector.introspect(...)`
  are blocking JDBC; call them on the `blockingScheduler` bean (`BlockingSchedulerConfig`) or
  `Schedulers.boundedElastic`, never the event loop.
- DTOs under `access/api/dto`; controller in the existing `AccessControlController` (or a dedicated
  controller under the same base path) with the same local `{status,message}` handler.
- Do not touch the policy engine, resolver, pipeline, SecurityConfig, users, or the schema.

## Tests

`web/AccessControlDataScopeTest` (`@SpringBootTest`, RANDOM_PORT):
- list empty; upsert FILTER on `campus/score/student_id/studentId` → 200; list returns it; re-upsert
  the same key as ALL (column/attribute null) → same id, policy changed;
- FILTER without a column → 400; ALL with a column → 400; unknown table/column/attribute → 422;
- unknown role/datasource → 404; student token → 403;
- DELETE → 204, list empty; delete unknown → 404;
- audit: one SUCCESS per upsert/delete with before/after, one DENIED for a rejected validation;
- next-request effect: assign the role to a fresh user, resolve via `AccessContextResolver`, assert
  the `DataScopeRule` is present and the policy engine returns it.

## Constraints

- Java 25 / Spring Boot 4.1 WebFlux; no new dependencies.
- Fail closed; bound SQL only; never accept a raw predicate string.
- `mvn test` without `DASHSCOPE_API_KEY`; report BUILD SUCCESS + count. Commit.
