# Task 5.1 brief — role CRUD + audit infrastructure

Goal: the first admin API slice: role catalog CRUD with single-parent inheritance and cycle rejection,
built-in protection, immutable codes, and an audit row for every mutation. Permissions and data
scopes are later tasks; do not build them here.

## API (admin, already gated by `access.manage`)

Base: `/api/admin/access-control/roles`

- `GET /` → `AccessRoleView[]`, ordered by code.
- `POST /` body `AccessRoleRequest` → `AccessRoleView`, 201.
- `PUT /{id}` body `AccessRoleRequest` → `AccessRoleView`.
- No DELETE; disabling is `PUT {enabled:false}`. Built-in admin cannot be disabled.

```json
// AccessRoleRequest (create: code required; update: code must be absent or identical)
{ "code": "teacher", "name": "教师", "description": "...", "parentId": null, "enabled": true }
// AccessRoleView
{ "id": "...", "code": "teacher", "name": "教师", "description": "...",
  "parentId": null, "builtIn": false, "enabled": true }
```

Validation / errors:
- `code`: required on create, `^[a-z][a-z0-9_-]{1,63}$`; unique → 409 "角色编码已存在"; immutable
  (a different code on update → 409 "角色编码不可修改").
- `name`: required, ≤100 chars → 400.
- `parentId`: must exist (404 "父角色不存在"); setting it to the role itself or any descendant /
  ancestor cycle → 409 "会形成循环继承". A disabled parent is allowed (it contributes nothing at
  resolution time).
- built-in roles (`built_in = true`): `code` and `builtIn` are immutable; the built-in `admin` role
  cannot be disabled (409 "内置管理员角色不能停用"). Other built-in roles may be disabled/renamed.
- unknown id → 404.

## Audit

Add write-only audit infrastructure now (read API is task 5.6):
- `AccessAuditRepository.record(...)` inserts one `access_audit` row in the same transaction as the
  mutation: `actor_id`, `action` (`role.create`/`role.update`/`role.disable`), `entity_type=ROLE`,
  `entity_id`, `before_json`, `after_json`, `result` (`SUCCESS`/`DENIED`), `reason`; timestamps are
  epoch millis. Serialize the role fields only; never secrets.
- Every mutation writes exactly one row; a rejected request writes `DENIED` with the reason.

## Files / structure

- `access/api/AccessControlController.java` + `access/api/dto/{AccessRoleRequest,AccessRoleView}.java`
- `access/AccessAdminService.java` (validation, cycle detection, built-in protection, audit)
- `access/AccessAdminRepository.java` (interface) + `access/R2dbcAccessAdminRepository.java`
  (`@Repository`, `DatabaseClient`): role insert/update/find/list + audit insert. Keep
  `AccessControlRepository` read-only for resolution; do not change it.
- Reuse `AccessRole` (domain) and `AccessControlRepository.allRoles()` where convenient.
- Use `@Transactional`? The app DB is R2DBC; a `TransactionalOperator` or a single-statement
  mutation + audit append is acceptable. If the audit insert fails, the mutation may stand; log the
  failure (document it). Prefer a transaction if a `R2dbcTransactionManager` bean is available —
  check before adding one.

## Tests

`web/AccessControlRoleTest` (`@SpringBootTest`, RANDOM_PORT, WebTestClient):
- create a role → 201, appears in GET;
- duplicate code → 409;
- update name/description/parent → 200; change code → 409;
- self-parent and two-role cycle → 409;
- unknown parent → 404;
- disable a custom role → 200; disable built-in admin → 409;
- non-admin (student token) → 403;
- exactly one `access_audit` row per successful mutation and one `DENIED` row for a rejected
  code change (query `access_audit` with `DatabaseClient`).
Use the test seed + `AccessControlRepository.assignRoleByCode` to create an admin user, then
`TestAuth.login`.

## Constraints

- Java 25 / Spring Boot 4.1 WebFlux; no new dependencies.
- Fail closed; no raw SQL from the client; identifiers validated.
- Do not touch permissions/data-scope/user endpoints, SecurityConfig, the policy engine or the
  pipeline.
- Run `mvn test` without `DASHSCOPE_API_KEY`; report BUILD SUCCESS + count. Commit.
