# Task 5.6 brief — audit read API

Goal: expose the access-control audit trail read-only, gated by `audit.read`, with filters and
pagination. All mutations already write rows (5.1–5.5); this task adds the read side and closes any
missing write coverage.

## API (base `/api/admin/access-control`, `audit.read`-gated)

- `GET /audit?actorId=&action=&entityType=&entityId=&result=&from=&to=&page=0&size=50`
  → paginated view, newest first:
  ```json
  {
    "items": [
      { "id": "...", "actorId": "...", "action": "role.update", "entityType": "ROLE",
        "entityId": "...", "before": { ... }, "after": { ... },
        "result": "SUCCESS", "reason": null, "createdAt": 1699999999999 }
    ],
    "total": 120, "page": 0, "size": 50
  }
  ```
- Filters:
  - `actorId`, `entityId`: exact match;
  - `action`: exact match (`role.create`, `role.update`, `role.disable`, `role.permissions`,
    `role.datasources`, `data_scope.upsert`, `data_scope.delete`, `user.create`, `user.enable`,
    `user.disable`, `user.password_reset`, `user.roles`, `user.update`);
  - `entityType`: exact match (`ROLE`, `USER`, `DATA_SCOPE`);
  - `result`: `SUCCESS` or `DENIED`; anything else → 400 "结果过滤值无效";
  - `from`/`to`: epoch millis, inclusive `from`, exclusive `to`; `from > to` → 400
    "时间范围无效";
  - unknown filters are ignored.
- Pagination: defaults page=0, size=50; size capped at 200; negative page/size or size=0 → 400
  "分页参数无效". Ordered by `created_at DESC, id DESC` (id breaks same-millisecond ties
  deterministically).
- `before`/`after`: parse the stored JSON text with Jackson and return it as JSON; `null` stays
  `null`; malformed stored JSON must not 500 — return the raw string or `null` (pick one and
  document it). Never include password material (the writers already guarantee this; add a test that
  scans the read output for `password`/`password_hash`).
- No write/delete endpoints: the audit API is read-only.

## Implementation

- Extend `AccessAdminRepository` + `R2dbcAccessAdminRepository` with a paginated query
  (`count` + `limit/offset`, bound parameters only, optional filters assembled as static fragments
  with bound values — no client-built SQL).
- New `AuditQueryService` (or similar) and DTOs under `access/api/dto`:
  `AccessAuditView`, `AccessAuditPageView`.
- Add `audit.read` to the route gating in `SecurityConfig` **before** the generic `/api/admin/**`
  matcher (same pattern as 5.4's `user.read`/`user.manage`): `GET /api/admin/access-control/audit`
  requires `Permissions.AUDIT_READ`. Everything else stays as is.
- Reuse the controller-local `{status,message}` handler.
- Do not change the writers, the resolver, the policy engine, the pipeline, the schema, or any
  existing audit rows.

## Write-coverage check

Audit every existing mutation site (`AccessAdminService`, `RolePermissionService`, `DataScopeService`,
`UserAdminService`) and confirm each writes exactly one SUCCESS row in the mutation transaction and
one DENIED row (REQUIRES_NEW) for rejections decided against a loaded entity. Add only missing rows
needed for this task; list what you verified. Do not restructure the existing transaction helpers.

## Tests

`web/AccessControlAuditTest` (`@SpringBootTest`, RANDOM_PORT, WebTestClient):
- an admin (with `audit.read`) sees rows produced by role/permission/data-scope/user mutations:
  actor, action, entity type/id, result, parsed before/after, timestamp;
- DENIED rows from rejected role/permission/data-scope/user changes are visible with the reason;
- each filter narrows correctly (`actorId`, `action`, `entityType`, `entityId`, `result`,
  `from`/`to`); invalid `result`/time range/pagination → 400 with the exact messages;
- pagination: create enough rows (or reuse the suite's rows), assert `total`, page 2, stable
  newest-first order, no overlap;
- a user without `audit.read` → 403; `user.read`/`user.manage`/`access.manage` alone are not enough;
- read output contains no `password`, `passwordHash`, `password_hash`, or token values.

## Constraints

- Java 25 / Spring Boot 4.1 WebFlux; no new dependencies (Jackson is already present).
- Fail closed; bound SQL only.
- `mvn test` without `DASHSCOPE_API_KEY`; report BUILD SUCCESS + count. Commit.
