# Task 5.7 implementer report

Status: DONE. Full backend suite: 353 tests, 0 failures, BUILD SUCCESS (no DASHSCOPE_API_KEY).
Orchestrator independently re-ran the suite: 353 tests, BUILD SUCCESS.

## Changes

- `DefaultPermissionEscalationGuard` (`@Component`): actor permissions via `AccessEscalationService`;
  built-in admin exempt; otherwise missing key → 403 `不能授予自己未持有的权限：<key>`; unresolvable
  actor fails closed.
- `AccessEscalationService` (new): actor permissions, built-in-admin detection, subset checks, role
  effective-permission lookup; reuses `AccessContextResolver` (new `effectiveRoleIdsOf`,
  `effectiveRoleIdsFor`, `effectivePermissionsForRoles`; shared package-static walk).
- `AccessLockoutService` (new): in-memory after-change evaluation for role permission replacement,
  role disable and role reparent; 409 `不能移除最后一个拥有 access.manage 的管理员`.
- `AccessAdminService`: parent must exist → enabled → subset of actor; built-in admin cannot be
  reparented; disable/reparent lockout.
- `RolePermissionService`: built-in admin cannot lose `access.manage`/`access.bypass_data_scope`;
  replacement lockout.
- `UserAdminService`: assigned roles' effective permissions must be ⊆ actor's
  (`403 不能分配超出自身权限的角色：<code>`).
- Repository: `rolePermissionLinks()`, `enabledUserRoles()` (bound SQL), plus link records.
- `AccessControlEscalationTest` (10 tests) + updates to role/user tests for the tightened rules.

## Behavior changes (intentional)

- Disabled parent is rejected (`409 父角色已停用`) instead of silently contributing nothing.
- Built-in admin may be used as a parent when the actor holds its permission set (subset rule);
  the built-in admin role itself still cannot be reparented.
- A `user.manage`-only actor can no longer assign roles whose permissions exceed their own; the test
  now assigns the manager's own role.

## Concerns

- Disabled role assignment is still permitted (empty effective set ⊆ actor); a later enable could
  grant latent permissions.
- Lockout TOCTOU remains (in-memory snapshot, not atomic with the mutation), as documented.
