# Task 5.5 brief — role → datasource matrix on role records

Goal: expose the role → datasource whitelist through the access-control API, keyed by role records
(not role-code constants), so custom roles can be granted datasources. Keep the legacy
`/api/admin/datasource-access` endpoint working for the current UI and dual-writing the same rows.

## API (base `/api/admin/access-control`, `access.manage`-gated)

- `GET /datasource-access` → the full matrix for the admin page:
  ```json
  {
    "roles": [
      { "id": "...", "code": "student", "name": "学生", "builtIn": true, "enabled": true,
        "datasourceIds": ["campus"] }
    ],
    "datasources": [ { "id": "campus", "name": "智慧校园", "default": true } ]
  }
  ```
  Roles ordered by code; every role (built-in or custom) appears; `datasourceIds` sorted. Only
  registered datasources appear; stored grants for a removed datasource are ignored (not an error).
- `GET /roles/{id}/datasources` → `{ "roleId": "...", "datasourceIds": ["campus"] }`.
- `PUT /roles/{id}/datasources` body `{ "datasourceIds": ["campus", "erp-prod"] }` → the same shape.
  - unknown role → 404 "角色不存在";
  - unknown datasource → 404 "数据源不存在：<id>" (validate against `DataSourceRegistry`);
  - de-duplicate; empty array clears every grant;
  - replace the set atomically and write one `access_audit` row
    (`action = role.datasources`, `entity_type = ROLE`, `entity_id = roleId`,
    before/after = sorted id arrays, SUCCESS), in the same transaction;
  - a rejected request writes a `DENIED` row (validation-first 400s write no row, per 5.1).

## Semantics

- `access_role_datasource` is the single enforcement source; `DatasourceAccessService.datasourceIds`
  already reads it by role id, so a change is effective on the next request without re-login.
  Do not change the resolver.
- Admins bypass datasource grants by definition; do not synthesize rows for them, but the matrix may
  show their grants if present (they are harmless).
- Legacy `datasource_access` and `/api/admin/datasource-access` stay for backward compatibility:
  student/counselor only, same dual-write order (grant legacy row → enforcement row; revoke
  enforcement row → legacy row). Do not remove or re-route that controller. Its grants and the new
  matrix share `access_role_datasource`, so they must be consistent; add a test.
- The one-time migration already backfills `access_role_datasource` from `datasource_access`
  (`db/migration/2026-11-09-access-control.sql`); no schema change is needed.

## Implementation

- Extend `AccessAdminRepository` + `R2dbcAccessAdminRepository`: `datasourceIdsOf(roleId)`,
  `datasourceGrants()` (role id + datasource id rows), `replaceRoleDatasources(roleId, ids)`.
  Bound SQL only; delete-then-insert per id like `replaceRolePermissions`.
- Prefer a dedicated `RoleDatasourceService` (like `RolePermissionService`/`DataScopeService`);
  reuse the 5.1 transaction/audit helpers and the controller-local `{status,message}` handler.
- DTOs under `access/api/dto`: `DatasourceMatrixView`, `RoleDatasourcesView`,
  `RoleDatasourcesRequest`, plus a role/datasource row record.
- Do not touch SecurityConfig, the policy engine, the resolver, the pipeline, data scopes, users,
  the schema, or the legacy `DatasourceAccessController`/`DatasourceAccessService` behaviour.

## Tests

`web/AccessControlDatasourceMatrixTest` (`@SpringBootTest`, RANDOM_PORT, WebTestClient):
- matrix lists built-in roles with their seeded grants and the registered datasources;
- a fresh custom role starts empty; PUT grants `campus` (+ a second datasource) → 200, GET and matrix
  reflect it; re-PUT replaces (does not union); empty clears;
- unknown role/datasource → 404 with the exact messages; duplicate ids de-duplicated; student → 403;
- audit: one SUCCESS per replace with sorted before/after; one DENIED for an unknown datasource;
- next-request effect: assign the custom role to a fresh user, resolve via `AccessContextResolver`,
  assert `datasourceIds` contains the grant; revoke and assert it is gone immediately;
- legacy compatibility: `PUT /api/admin/datasource-access/student/campus` (or revoke) keeps working
  and the new role-record matrix reflects the same underlying grant.

## Constraints

- Java 25 / Spring Boot 4.1 WebFlux; no new dependencies.
- Fail closed; bound SQL only; no client-built grant semantics beyond ids.
- `mvn test` without `DASHSCOPE_API_KEY`; report BUILD SUCCESS + count. Commit.
