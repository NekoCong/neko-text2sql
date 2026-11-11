# Task 5.2 implementer report

Status: DONE. Full backend suite: 287 tests, 0 failures, BUILD SUCCESS (no DASHSCOPE_API_KEY).

## Changes

- `access/AccessPermission` domain record (id, key, module, action, description, builtIn).
- DTOs: `AccessPermissionView`, `AccessRolePermissionsView`, `AccessRolePermissionsRequest`.
- `AccessAdminRepository`/`R2dbcAccessAdminRepository`: `permissions()` (module,key ordered), `permissionKeysOf(roleId)`, `replaceRolePermissions(roleId, permissionIds)` (delete + one bound insert per key).
- `AccessAdminService`: `permissions()`, `permissionsOf(roleId)` (404 unknown role), `replacePermissions(actorId, roleId, keys)` — validates/trims id, caps 256 keys/length, de-dupes/sorts, validates against the catalog (400 未知权限：<key>), replaces set + one SUCCESS `role.permissions` audit row in a `TransactionalOperator`; DENIED row for unknown role/key via the REQUIRES_NEW helper.
- `PermissionEscalationGuard` seam + `permitAll()` default (5.7 hook).
- `AccessControlController`: explicit role path constants so `/permissions` and `/roles/{id}/permissions` fit; existing role routes unchanged.
- Tests: `AccessAdminServiceTransactionTest` stub extended; `AccessControlPermissionTest` (9) covers catalog, read/subset replace, dedupe, empty clear, unknown role/key, student 403, SUCCESS/DENIED audit rows, next-request effect via `AccessContextResolver`.

## Verification

- `mvn test` (no key) → 287 tests, 0 failures, BUILD SUCCESS.

## Concerns

- Read endpoint is not audited; only mutations write rows (matches 5.1).
- Catalog test asserts the nine built-in keys, not an exact count (the shared H2 DB has an extra non-built-in row from `R2dbcAccessControlRepositoryTest`).
- Shape rejections return 400 without an audit row per the 5.1 validation-first rule.
- `PermissionEscalationGuard` is permissive and only wired via the 4-arg constructor; 5.7 must register the real implementation.
