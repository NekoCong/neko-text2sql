# Task 5.4 fix report — user lifecycle admin API

Review outcome addressed: **approved with one missing brief test + minors**. All items below are
in commit on `master`; covering class `AccessControlUserTest` is now 13 tests.

## Required

1. **Role changes do not bump `token_version` and take effect on the next request.**
   - Added `roleChangesTakeEffectOnTheNextRequestWithoutBumpingTokenVersion` (HTTP-level).
   - Creates a user with the `student` role (no `access.manage`), logs in and keeps the token;
     asserts `GET /api/admin/access-control/roles` is 403 with that token.
   - As admin, replaces the user's roles with a fresh role granting `access.manage`
     (`createRoleGranting(Permissions.ACCESS_MANAGE)`); asserts 200 and that the DB
     `token_version` is unchanged.
   - With the *same* token, `GET /api/admin/access-control/roles` now returns 200 (no re-login),
     because permissions are resolved from the database on every request.
   - Removes the role again and asserts the same token is once more 403, with `token_version`
     still unchanged.
   - Note: `datasource.read` is a catalog-only permission with no HTTP route gate in this
     codebase, so the test uses `access.manage` (gated by `PermissionAuthorizationManager` on
     `/api/admin/**`) to observe the effect through a real route.

## Minors

- **Duplicate-username race returns 409, not 500.** The user insert is now wrapped in
  `onErrorResume(DuplicateKeyException.class, …)` → `duplicateRace(...)`, which reloads the
  winning row in a `REQUIRES_NEW` transaction, writes an audited `user.create` DENIED row
  (`entity_id` = winning id), and fails with 409 `用户名已存在`. If the winning row cannot be
  located the conflict is logged and still answered 409 (no audit). The pre-read
  (`userByUsername`) remains the friendly path.
- **Honest `roleCodes` in unknown-role denials.** `create` and `replaceRoles` no longer pass raw
  `roleIds` into the denial payload. Both now resolve the known ids to their codes via
  `knownRoleCodes(...)` (unknown ids stay absent), so `after_json.roleCodes` contains codes, not
  ids.
- **Test gaps.** Added `anUnknownUserIsNotFound` (status/reset/roles → 404 `用户不存在`) and
  `aNoOpStatusChangeReturns200AndWritesNoAuditRow` (no-op enable/disable writes no audit row; the
  real disable still writes exactly one).

## Validation

- `mvn test -Dtest=AccessControlUserTest` — 13 run, 0 failures.
- `mvn test` (no `DASHSCOPE_API_KEY`) — **BUILD SUCCESS**, Tests run: **328**, Failures: 0,
  Errors: 0, Skipped: 0.

## Notes / residual concerns

- The `DuplicateKeyException` race path is defensive: it only fires when the pre-read loses a
  concurrent insert race, which is not deterministically exercised by the HTTP-level tests. The
  friendly duplicate path (pre-read → 409 `用户名已存在` + DENIED row) remains covered by
  `rejectsADuplicateUsernameAndAnUnknownRole`.
- The permission-gated route used by the token-version test is `/api/admin/access-control/roles`
  (`access.manage`); `datasource.read` is currently unenforced at any HTTP route, so it was not
  used to avoid asserting a no-op.

## Fix round 2 (spec/design cross-check — 3 gaps)

1. **User routes gated by `user.read` / `user.manage`, not `access.manage`.**
   `SecurityConfig` now maps `GET /api/admin/access-control/users` (list) and `GET
   /api/admin/access-control/users/*` (detail) to `Permissions.USER_READ`, and `POST
   /api/admin/access-control/users` + `/api/admin/access-control/users/**` (create / display-name
   / status / reset-password / roles) to `Permissions.USER_MANAGE`. These matchers precede the
   generic `/api/admin/**` → `access.manage` rule, so roles/permissions/data-scopes stay on
   `access.manage`. Helpers `userRead()`/`userManage()` mirror the file's existing style; the JWT
   role claim is not consulted.
2. **Pagination on the user list.** `GET /users` now takes `page`/`size` (defaults 0/20, `size`
   capped at 100; negative `page`/`size` or `size=0` → 400 `分页参数无效`) and returns
   `{items, total, page, size}`. `total` is a `COUNT(*)` over the same filters and paging is
   applied in SQL (`LIMIT :size OFFSET :offset`, ordered by `username, id`), never in memory.
   New DTO `AccessUserPage`; repository `users(...)` gained `size`/`offset` and a `countUsers`
   method (shared `WHERE` builder).
3. **Display-name edit + detail.** Added `PUT /users/{id}` (body `{displayName}`; null/blank
   clears; ≤100 else 400) auditing `user.update` with before/after `{displayName}` only and no
   `token_version` bump, and `GET /users/{id}` (unknown → 404 `用户不存在`). New DTO
   `AccessUserDisplayNameRequest`; repository `updateUserDisplayName`.

Tests added in `AccessControlUserTest` (now 18): `listIsPaginatedInSqlWithATotalAndNoOverlap`
(3 users, size 2 → 2 pages, total 3, no overlap), `editsAndClearsTheDisplayNameWithAuditAndNoTokenBump`,
`readsOneUserAndReturnsNotFoundForUnknown`, `aUserReadOnlyRoleCanReadButNotWrite`, and
`aUserManageOnlyRoleCanWriteButNotRead`. Existing list assertions were updated for the envelope.

## Validation

- `mvn test -Dtest=AccessControlUserTest` — 18 run, 0 failures.
- `mvn test` (no `DASHSCOPE_API_KEY`) — **BUILD SUCCESS**, Tests run: **333**, Failures: 0,
  Errors: 0, Skipped: 0.

## Notes / residual concerns

- `LIMIT :size OFFSET :offset` uses bound parameters, verified against H2 (the test dialect).
  Production MySQL also accepts parameterised `LIMIT ? OFFSET ?`; no literal is interpolated.
- The `total` JSON field is an integer; the test reads it via `Number` to stay agnostic of the
  Jackson `Integer` vs `Long` deserialization choice.
