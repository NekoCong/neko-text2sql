# Task 5.4 brief — user lifecycle admin API

Goal: let an administrator manage users end-to-end — list, create, disable/enable, reset password,
and assign roles — with audit and lockout protection. Extend the existing access-control admin slice;
do not build data scopes or the audit read API.

## API (base `/api/admin/access-control`, `access.manage`-gated)

- `GET /users?query=<text>&enabled=<bool>&roleId=<id>` → `AccessUserView[]`, ordered by username.
  `query` matches username/display name case-insensitively (contains); missing filters mean "any".
- `POST /users` body `AccessUserCreateRequest` → `AccessUserView`, 201.
- `PUT /users/{id}/status` body `AccessUserStatusRequest` → `AccessUserView`.
- `POST /users/{id}/reset-password` body `AccessUserPasswordRequest` → 204.
- `PUT /users/{id}/roles` body `AccessUserRolesRequest` → `AccessUserView`.

```json
// AccessUserCreateRequest
{ "username": "s-1001", "password": "secret123", "displayName": "张三", "roleIds": ["..."] }
// AccessUserStatusRequest
{ "enabled": false }
// AccessUserPasswordRequest
{ "password": "newsecret123" }
// AccessUserRolesRequest
{ "roleIds": ["...", "..."] }
// AccessUserView
{ "id": "...", "username": "s-1001", "displayName": "张三", "enabled": true,
  "roles": [ { "id": "...", "code": "student", "name": "学生" } ], "createdAt": 1699999999999 }
```

Validation:
- `username`: required, `^[A-Za-z0-9._-]{3,64}$`; unique → 409 "用户名已存在".
- `password`: required, 8–72 chars → 400 "密码长度必须为 8-72 个字符". Hash with the existing
  `PasswordEncoder` bean; never log, audit, or return it.
- `displayName`: optional, ≤100; blank becomes null.
- `roleIds`: create requires at least one; every id must exist → 404 "角色不存在：<id>"; duplicates
  de-duplicated. Role ids, not codes, in the API.
- unknown user → 404 "用户不存在".
- `enabled` required boolean; no-op change still returns 200 and writes no audit row.
- **Lockout protection:** disabling a user or changing their roles is rejected with 409
  "不能移除最后一个拥有 access.manage 的管理员" if that user currently holds `access.manage`
  (directly or through inherited roles) and, after the change, no other *enabled* user would
  still hold it. Admin = any user with the permission, not the legacy role column. The acting
  user may be the subject; the rule still applies.

## Data rules

- Authorization comes only from `access_user_role` (+ inheritance); the legacy `app_user.role`
  column is display/migration only. New users keep the column default `'student'`; do not let the
  client set it.
- Disabling, enabling and password reset bump `app_user.token_version` in the same transaction as
  the row update, so existing JWTs are rejected on the next request.
- Role changes do **not** bump `token_version`; permissions are resolved from the database on every
  request. State this in the service Javadoc and cover it with the next-request test.
- No DELETE: disabling is the lifecycle end.

## Audit

Reuse the 5.1 patterns (`TransactionalOperator` for mutation + SUCCESS row; `REQUIRES_NEW` for
`DENIED`). Actions: `user.create`, `user.enable`, `user.disable`, `user.password_reset`,
`user.roles`; `entity_type = USER`, `entity_id = user id`.
- `before_json`/`after_json` contain only `{username, displayName, enabled, roleCodes[]}` — never
  password_hash or password material. Password reset writes `"password": "changed"` at most.
- Successful create/status/reset/roles each write exactly one SUCCESS row; a rejected request
  (unknown role, last-admin, duplicate username decided against the loaded state) writes one DENIED
  row with the reason. Pure shape 400s write no row (5.1 rule).

## Implementation

- Extend `access/AccessAdminRepository` + `R2dbcAccessAdminRepository` (user list/find/insert,
  status update with token bump, password update with token bump, role replacement) and prefer a
  dedicated `UserAdminService` (like `RolePermissionService` / `DataScopeService`) — keep existing
  services focused.
- Reuse `UserRepository`/`AuthService` hashing conventions where sensible; do not change their
  public behaviour.
- Effective-permission check for the lockout rule must reuse `AccessContextResolver` /
  `DataScopeResolver` (or the same query path) rather than reimplementing inheritance.
- DTOs under `access/api/dto`; endpoints in `AccessControlController` using the existing local
  `{status,message}` handler and `actorId()` helper.
- Bound SQL only; no schema changes; no new dependencies; do not touch SecurityConfig, the policy
  engine, the pipeline, roles/permissions/data-scope services, or the audit read path.

## Tests

`web/AccessControlUserTest` (`@SpringBootTest`, RANDOM_PORT, WebTestClient):
- create → 201, appears in GET with its role; duplicate username → 409; unknown role → 404;
  password only hashed in the DB (assert `password_hash` is a bcrypt hash, not the raw value);
- filters: query, enabled, roleId;
- disable → 200, `enabled=false`, `token_version` bumped, existing token rejected on the next
  request; enable → 200, bumped again;
- reset password → 204, old password fails, new password logs in, token_version bumped;
- roles: replace → 200, reflected; empty clears; unknown role → 404;
- lockout: the last enabled `access.manage` user cannot be disabled or stripped of the role → 409;
  with a second admin, the same calls succeed;
- non-admin (student) → 403 on every route;
- audit: one SUCCESS row per mutation with secret-free before/after; one DENIED for a rejected
  role change or last-admin attempt.

## Constraints

- Java 25 / Spring Boot 4.1 WebFlux; no new dependencies.
- Fail closed; bound SQL; never return or audit password material.
- `mvn test` without `DASHSCOPE_API_KEY`; report BUILD SUCCESS + count. Commit.
