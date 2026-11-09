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
