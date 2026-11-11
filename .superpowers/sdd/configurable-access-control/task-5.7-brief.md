# Task 5.7 brief — anti-escalation and lockout protection

Goal: enforce the change's escalation and lockout invariants now that all admin surfaces exist:
- a non-built-in administrator cannot grant permissions (directly or via role inheritance/assignment)
  that they do not themselves hold;
- the system always keeps at least one usable `access.manage` holder;
- the built-in `admin` role stays protected (no disable, no reparent, no permission removal that
  breaks bootstrap);
- route gating stays: `access.manage` for roles/permissions/data scopes/datasource matrix,
  `user.read`/`user.manage` for users (already implemented in 5.1–5.6 — verify, do not re-route).

## 1. Permission-grant anti-escalation

Implement the existing `PermissionEscalationGuard` seam as a real bean (the 5.2 seam is currently
`permitAll()`; the role-permission service already injects it through `ObjectProvider`):

- `DefaultPermissionEscalationGuard`:
  - resolve the actor's effective permissions (`AccessContextResolver.effectivePermissions`);
  - if the actor holds the built-in `admin` role, allow everything (bootstrap exception);
  - otherwise deny any requested key not in the actor's set with
    `403 "不能授予自己未持有的权限：<key>"`;
  - if the actor cannot be resolved (no roles/unknown), fail closed (deny).
- The role-permission endpoint must already audit the denial (`DENIED` + reason) and return 403;
  verify with a test.

## 2. Inheritance / role-assignment anti-escalation

A role's effective permissions include its ancestors, so the guard must cover the parent edge and
user role assignment, not just the permission matrix:

- `AccessAdminService.create/update` (role catalog):
  - loading/validating a `parentId` must also check that the parent's effective permission set is a
    subset of the actor's (built-in admin actor exempt); otherwise `403 "不能继承超出自身权限的角色：<code>"`;
  - the built-in `admin` role cannot be reparented at all (its `parentId` must stay `null`) →
    `409 "内置管理员角色不能调整继承关系"`; changing its `enabled`, `code` already rejected;
  - reject a parent that is disabled? It contributes nothing today, but enabling it later would
    escalate silently. Document the choice; prefer rejecting disabled parents with
    `409 "父角色已停用"` (simpler, safer) unless a concrete reason says otherwise.
- `UserAdminService.create/replaceRoles`:
  - every assigned role's effective permission set must be a subset of the actor's (built-in admin
    actor exempt) → `403 "不能分配超出自身权限的角色：<code>"`; audit `DENIED` with the role codes;
  - this includes assigning the built-in admin role from a non-admin actor.
- Add a shared escalation component (e.g. `AccessEscalationService`) used by the role service, the
  user service and the permission guard; do not duplicate inheritance walks — reuse
  `AccessContextResolver` helpers (extend the resolver with `effectiveRoleIdsFor(Collection)` /
  `effectivePermissionsForRoles(...)` rather than reimplementing `addWithParents`).

## 3. Lockout protection beyond user mutations

5.4 protects disabling a user and changing their roles. Close the remaining routes:

- **Role permission replacement:** reject `409 "不能移除最后一个拥有 access.manage 的管理员"` when
  applying the new key set would leave no *enabled* user holding `access.manage` (account for
  inheritance: the edited role may be a parent of other roles/users).
- **Role disable:** same rejection if disabling the role would leave no enabled holder.
- **Role reparent:** same rejection if the new parent graph would leave no enabled holder.
- **Built-in admin role:** already cannot be disabled; also reject removing
  `access.manage`/`access.bypass_data_scope` from it (it is the bootstrap role) — either via the
  permission endpoint (`409`/`403` with a clear reason) or by exempting it from the subset rule and
  never allowing its set to lose those keys. Pick one and document it.
- **User disable / role change:** keep 5.4's rule; optionally refactor it onto the shared lockout
  component, but do not regress the existing tests.
- All lockout rejections write exactly one `DENIED` audit row with the reason.

Implementation note: a single in-memory "after" evaluation is acceptable at admin scale — load all
roles, role-permission links, enabled users and their roles, apply the proposed change, compute each
enabled user's effective permission set with the existing inheritance walk, and require at least one
`access.manage`. Add the read-only repository methods this needs (bound SQL). A recursive SQL query
is not required and would hurt H2/MySQL portability. The check is not strictly atomic with the
mutation (same as 5.4); document that residual TOCTOU and keep the mutation transactional.

## 4. Verify route gating

Add/keep tests that:
- role/permission/data-scope/datasource-matrix routes require `access.manage` (not `user.manage`);
- user list/detail require `user.read`; user writes require `user.manage`;
- a role with only `user.manage` cannot edit roles or permissions.

## Tests

Prefer extending `web/AccessControlRoleTest`, `AccessControlPermissionTest`,
`AccessControlUserTest`, and a new `web/AccessControlEscalationTest` (RANDOM_PORT, WebTestClient,
real HTTP + `DatabaseClient` audit assertions):

- non-admin actor with `access.manage` but not `access.bypass_data_scope` cannot grant that key to a
  role (403 + DENIED); the built-in admin can;
- non-admin actor cannot create/update a role whose parent grants a permission they lack (403);
- non-admin actor cannot assign such a role to a user (403 + DENIED with role codes);
- built-in admin role: reparent → 409; disable → 409; removing `access.manage` from it → 409/403;
- last-admin lockout: with one enabled holder, role-permission removal, role disable and role
  reparent are each rejected 409; with a second admin they succeed;
- every rejection writes exactly one DENIED row; successful changes still write SUCCESS;
- existing 5.1–5.6 suites stay green.

## Constraints

- Java 25 / Spring Boot 4.1 WebFlux; no new dependencies.
- Fail closed; bound SQL only; no raw SQL from clients.
- Do not change the policy engine, the pipeline, the resolver's resolution semantics, the schema,
  or the user-route gating from 5.4.
- `mvn test` without `DASHSCOPE_API_KEY`; report BUILD SUCCESS + count. Commit.
