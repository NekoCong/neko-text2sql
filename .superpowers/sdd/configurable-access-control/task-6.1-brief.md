# Task 6.1 brief — access-control API client + effective permissions in the profile

Goal: give the frontend a complete, typed client for the access-control API, and make the signed-in
profile carry the caller's effective permissions/roles so navigation and route guards can use them
(the legacy `role` claim stays display-only).

## Backend (small, required before the UI)

- `UserView` (used by `/api/auth/me`, login and register) gains two additive fields:
  ```json
  { "id": "...", "username": "admin", "displayName": "...", "role": "admin",
    "roles": ["admin"], "permissions": ["access.manage", "audit.read", "..."] }
  ```
  - `roles` = effective role **codes** (enabled direct roles + enabled ancestors), sorted;
  - `permissions` = effective permission keys, sorted;
  - both resolved from the database on every call (no JWT claims, no cache).
- Add `AccessContextResolver.effectiveRoleCodes(userId)` reusing the existing inheritance walk; do not
  duplicate it.
- `AuthController`: `/me`, login and register return the enriched view. Keep the response shape
  additive; existing clients must keep working. Do not change the JWT contents.
- Tests: `/me` for the seeded admin contains `access.manage`, `user.manage`, `audit.read`;
  registration returns `student` + `chat.use` + `datasource.read`; a user whose role was changed sees
  the new permissions on the next `/me`; no password material.

## Frontend (no UI changes in this task)

- `src/types.ts`: extend `AuthUser` with `roles?: string[]` and `permissions?: string[]`; add the
  access-control types mirroring the backend DTOs exactly:
  `AccessRole`, `AccessRoleRequest`, `AccessPermission`, `AccessRolePermissions`,
  `DataScope`, `DataScopeRequest`, `RoleDatasources`, `DatasourceMatrix` (roles + datasources),
  `AccessUser`, `AccessUserRole`, `AccessUserPage`, `AccessUserCreateRequest`,
  `AccessUserDisplayNameRequest`, `AccessUserStatusRequest`, `AccessUserPasswordRequest`,
  `AccessUserRolesRequest`, `AccessAudit`, `AccessAuditPage`, `AuditFilters`.
  Use the exact backend field names (`builtIn`, `datasourceId`, `tableName`, `principalAttribute`,
  `createdAt`, `displayName`, `roleId`, …).
- `src/api/accessControl.ts` with one function per endpoint (base `/api/admin/access-control`):
  - roles: `listRoles`, `createRole`, `updateRole`
  - permissions: `listPermissions`, `getRolePermissions`, `replaceRolePermissions`
  - data scopes: `listDataScopes({roleId, datasourceId?})`, `upsertDataScope`, `deleteDataScope`
  - datasource matrix: `getDatasourceMatrix`, `getRoleDatasources`, `replaceRoleDatasources`
  - users: `listUsers({query?, enabled?, roleId?, page?, size?})`, `getUser`, `createUser`,
    `updateUserDisplayName`, `updateUserStatus`, `resetUserPassword`, `replaceUserRoles`
  - audit: `listAudit(filters)`
  Use the shared `http` instance; page/query params passed as an axios `params` object (never
  string-concatenated); no `any`.
- Auth store: add a `can(permission: string): boolean` getter backed by `user.permissions`, and an
  `isAdmin` getter based on `access.manage` (do not use `user.role`). Keep the legacy `role` field
  for display.
- Frontend tests: extend the existing node test setup with a pure unit test for `can()`/`isAdmin`
  (extract the predicate into a small helper if the store is hard to test), and ensure
  `npm run build` passes.

## Constraints

- Java 25 / Spring Boot 4.1 WebFlux; Vue 3 + TypeScript + Element Plus; no new dependencies.
- Fail closed; no JWT-claim authorization; no raw SQL.
- Do not build any UI pages/routes in this task (6.2–6.8 own that).
- Backend: `mvn test` without `DASHSCOPE_API_KEY` → BUILD SUCCESS + count.
- Frontend: `npm run build` and `npm test` → success. Commit both.
