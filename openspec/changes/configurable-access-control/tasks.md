# Tasks

## 1. Access-control schema and seed

- [x] 1.1 Add `access_role`, `access_permission`, `access_role_permission`, `access_user_role`, `access_role_datasource`, `access_data_scope`, and `access_audit` to `text2sql-backend/src/main/resources/db/app-schema.sql` with the indexes needed by the resolver (user, role, datasource/table). Verify a fresh schema applies cleanly and `SHOW TABLES` lists all seven.
- [ ] 1.2 Seed the built-in `admin`, `student`, and `counselor` roles, the permission catalog (including `user.read` and `user.manage`), role-permission mappings, datasource grants, and data-scope policies equivalent to today's `TablePolicy` in `db/app-data.sql` using idempotent inserts. Verify a fresh DB has exactly one built-in admin role and the expected permission/scope counts.
- [ ] 1.3 Add an idempotent migration script under `text2sql-backend/src/main/resources/db/migration/` that creates the new tables if absent and backfills `access_user_role` from `app_user.role`, `access_role_datasource` from `datasource_access`, and `access_data_scope` from the current role behavior. Verify running it twice produces no duplicate rows and leaves the legacy tables untouched.
- [ ] 1.4 Add `enabled BOOLEAN NOT NULL DEFAULT TRUE` and `token_version INTEGER NOT NULL DEFAULT 0` to `app_user` (schema + idempotent migration for existing volumes). Verify a fresh schema has both columns, existing rows backfill with `TRUE`/`0`, and the migration adds them to an existing table.

## 2. Access-control domain and resolution

- [x] 2.1 Add the role, permission, user-role, datasource-grant, data-scope, and audit records/DTOs plus R2DBC repositories. Verify repository tests cover insert, update, disable, and lookup by user/role/table.
- [x] 2.2 Implement effective-access resolution: load enabled roles and ancestors, union permissions, reject inheritance cycles, resolve principal attributes from `user_scope`, and fail closed when no usable role/policy exists. Verify unit tests for multi-role union, inheritance, cycle rejection, disabled parent, unknown role, and missing attributes.
- [x] 2.3 Implement the data-scope policy engine: `DENY` wins across roles, then `ALL`, then `OR` across `FILTER` policies; validate table/column/principal attribute; default deny for unconfigured tables. Verify unit tests for each merge case and for unknown table/column/attribute.
- [x] 2.4 Implement permission checks for module/function keys and the `access.bypass_data_scope` permission; make the built-in admin role carry it through seed data rather than a role-name branch. Verify a role with the permission gets unrestricted data and a role without it does not.

## 3. Enforcement integration

- [x] 3.1 Replace `DataScope`/`TablePolicy` usage in `Text2SqlPipelineService`, `SqlTools`, schema providers, and introspection with the effective-access context and policy engine. Verify pipeline and AgentScope tests produce the same filtered result for the same user.
- [x] 3.2 Update `SqlExecutor` and the pipeline guard so an unconfigured table, unknown policy, or missing access context fails before `executeQuery`. Verify no statement is executed for a denied table.
- [x] 3.3 Replace `scopedExplanation` and `callerContext` role branches with policy/role metadata from the effective-access context. Verify student/counselor/admin answers keep the same scope wording and no role-name helper remains in those methods.
- [x] 3.4 Replace `Roles.isAdmin(jwt)` checks in `SecurityConfig`, datasource admin controllers, and model-config controllers with permission checks. Verify non-admin users get 403 on management APIs and authorized custom roles get 200.
- [x] 3.5 Add account-status and session invalidation: login rejects disabled accounts; the JWT carries the user's `token_version` (`ver`); the access resolver rejects a disabled account or version mismatch on every request; disable, re-enable and password reset bump the version; self-registration assigns the seeded `student` role. Verify disabled login returns 401, an old token is rejected after disable and after reset, a re-enabled account still needs a new login, and a newly registered user has the student role.

## 4. SQL rewriting and CTE fix

- [x] 4.1 Fix CTE scoping in `DataScopeRewriter`: a non-recursive CTE's own name is not visible inside its body, only previously defined CTEs are; reject CTE names that collide with protected tables; reject recursive CTEs in this phase. Verify tests for same-name masking, schema-qualified body, nested CTE, legal CTE, and recursive rejection.
- [x] 4.2 Harden the row-predicate builder: quote identifiers, escape string values consistently with MySQL, and reject unknown/unsafe identifiers. Verify tests for quote/backslash handling and identifier rejection.
- [x] 4.3 Add a regression test that a restricted user's `WITH ... AS (SELECT * FROM protected_table)` query is rejected or filtered and returns no out-of-scope rows.

## 5. Admin API

- [x] 5.1 Add role CRUD and hierarchy endpoints under `/api/admin/access-control/roles`, including built-in protection, code immutability, and cycle rejection. Verify 200/403/409 cases and audit rows.
- [x] 5.2 Add permission catalog and role-permission matrix endpoints. Verify assigning/revoking a permission changes the next request's effective permission.
- [x] 5.3 Add data-scope endpoints with server-side validation against datasource metadata and the principal-attribute catalog. Verify invalid table/column/attribute and raw-SQL input are rejected with 422.
- [x] 5.4 Add user management endpoints under `/api/admin/access-control/users`: search/paginate (`user.read`), create with an initial password, edit the display name, disable/enable, reset password, and assign/remove roles (`user.manage`). Password fields are write-only and never echoed back. Verify create/duplicate-username/disable/enable/reset/role-assignment cases, that responses contain no password material, and that every mutation is audited.
- [x] 5.5 Migrate the datasource-access matrix to role records and expose it through the access-control API. Verify the existing grant/revoke behavior and the next-request effect.
- [x] 5.6 Add audit read endpoints and write audit rows for every access-control mutation, including denied escalation attempts and user lifecycle events without password material. Verify audit rows contain actor, before/after, result, and that `audit.read` is required.
- [x] 5.7 Implement anti-escalation and lockout protection: only `access.manage` manages roles/permissions/data scopes, `user.manage` manages users, no self-grant of permissions the actor lacks (including roles assigned during user creation), the last `access.manage` holder cannot be removed, disabled, or have the admin role revoked, and the built-in admin cannot be deleted/disabled. Verify each rejection with a test.

## 6. Frontend access-control area

- [ ] 6.1 Add TypeScript types and API client functions for roles, permissions, data scopes, users, datasource grants, and audit. Verify the client compiles and each function maps to the backend route.
- [ ] 6.2 Add `/admin/access-control` route, navigation entry, and a permission-aware route guard based on `access.manage`; keep the API as the security boundary. Verify a non-admin cannot see the entry or render the page, and a direct API call returns 403.
- [ ] 6.3 Build the Roles tab: list, create, edit, disable, parent-role selector, built-in badge. Verify create/edit/disable and cycle errors surface inline.
- [ ] 6.4 Build the Permissions tab: grouped permission catalog and role-permission matrix with save feedback. Verify toggling a permission persists and reloads correctly.
- [ ] 6.5 Build the Data scope tab: role/datasource/table selector, `ALL`/`FILTER`/`DENY`, column and principal-attribute selectors, and an effective-policy preview. Verify invalid combinations are blocked before save.
- [ ] 6.6 Build the Users and Datasource access tabs: user search/pagination, create-user dialog (username, display name, initial password, roles), edit display name, disable/enable, reset-password dialog, role assignment, and the migrated role-datasource matrix. Password inputs are write-only; the UI never displays a stored password. Verify changes persist, match backend state, and disabled users show the correct status.
- [ ] 6.7 Build the Audit tab: read-only table with actor, time, entity, result, and before/after expand. Verify `audit.read` gating and empty/error states.
- [ ] 6.8 Make navigation and page guards permission-aware across the app (chat, datasources, model settings, access control). Verify a user without a module permission does not see its entry and direct API calls still return 403.

## 7. Documentation and migration

- [ ] 7.1 Update `docs/nl2sql.md` with the configurable RBAC/data-scope model, the `DENY` > `ALL` > `FILTER` merge rule, and the CTE-safe enforcement note. Verify the documented examples match the implemented behavior.
- [ ] 7.2 Update `docs/deploy.md` with the migration script, backup/rollback steps, and the existing-volume warning (init scripts do not rerun). Verify the commands are runnable as written.
- [ ] 7.3 Update `README.md` with the admin access-control page (including user creation/disable/reset/role assignment), built-in roles, and the fact that role changes and account-status changes apply on the next request. Verify the described routes and seeds exist.

## 8. Integration verification

- [ ] 8.1 Run the backend test suite with JDK 25 (`.\mvnw.cmd test` or the project's documented command) and verify all tests pass, including the new access-control/CTE tests.
- [ ] 8.2 Run `npm test` and `npm run build` in `text2sql-frontend`; both must exit 0.
- [ ] 8.3 Apply the migration to a copy of the seed database and compare effective access for `admin`, `student01`, and `counselor01` with the pre-change behavior. Verify `student01` still sees only own rows, `counselor01` sees only managed classes, and `admin` is unrestricted.
- [ ] 8.4 End-to-end check: create a custom role, grant `chat.use` plus a filtered `score` policy, create a user with that role, and verify both the pipeline and AgentScope path return the filtered result; revoke the role and verify the next request is denied.
- [ ] 8.5 Run the CTE regression and confirm a restricted user cannot read a protected table via a same-name CTE, including a schema-qualified body.
- [ ] 8.6 Confirm the final diff contains no sandbox/container-isolation work, no approval workflow, and no dependency changes; update the change ledger with the results.
- [ ] 8.7 E2E account lifecycle: create a user with the `student` role, log in, disable the account and verify the next request with the same token returns 401/403, re-enable and verify the old token is still rejected, reset the password and verify login with the old password fails and the new one succeeds; confirm the audit rows contain no password material.

## Workflow follow-up

- Archive `campus-data-permission` first and reconcile its three-role requirement with the configurable model before archiving this change.
- Archive this change after the project review and the integration checks above are satisfied.
