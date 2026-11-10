# Design

## Context

See `proposal.md` for motivation. The current enforcement is correct for three fixed roles but hardcodes the role vocabulary in several places:

- `security/Roles` normalizes exactly `admin`, `student`, `counselor`.
- `security/DataScope` is a record whose `isAdmin()` / `isStudent()` / `isCounselor()` methods are used by `TablePolicy`, the explanation helper, and the caller-context helper.
- `security/TablePolicy` maps role names to table whitelists and row predicates.
- `security/DataScopeResolver` switches on the role name and requires role-specific `user_scope` values.
- `Text2SqlPipelineService` has `scopedExplanation` and `callerContext` branches on `scope.isAdmin()` / `isStudent()`.
- `SecurityConfig` and admin controllers use `Roles.isAdmin(jwt)`.
- `datasource_access` stores a role string; `user_scope` stores principal attributes.
- `app_user` stores a single `role` string and has no enabled flag or token version; `AuthService` only verifies the password, so a disabled account would still be able to use an unexpired JWT.

The completed-but-unarchived `campus-data-permission` change built the runtime enforcement chain: both the deterministic pipeline and the AgentScope path resolve a `DataScope` and rewrite SQL before execution. This change keeps that chain and replaces the hardcoded policy source with database records. The main spec for `data-permission` is not yet in `openspec/specs/`; see the compatibility note below.

Constraints:

- Java 25 / Spring WebFlux / R2DBC / MySQL 8.4; AgentScope 2.0.4.
- JSqlParser is already used by `DataScopeRewriter`; no new SQL parser is needed.
- The demo deployment initializes the app database from `db/app-schema.sql` and `db/app-data.sql`, which run only on an empty MySQL data directory.
- JWT currently carries a single `role` claim. Enforcement must not depend on a stale claim if an administrator changes roles.
- No approval workflow, SSO, column masking, or sandbox is introduced.

## Goals / Non-Goals

**Goals:**

- Make roles, permissions, role assignments, datasource grants, and data-scope policies data-driven and manageable from the admin UI.
- Remove role-name branching from enforcement; authorization is based on effective permissions and policy records.
- Keep the existing runtime guarantees: both paths use the same chokepoint, unknown policy fails closed, and a restricted user never executes an unfiltered statement.
- Make CTE handling safe: a CTE name cannot exempt a protected table from policy.
- Preserve the effective behavior of the three seeded roles during migration.
- Make access-control changes auditable and resistant to self-escalation and last-admin lockout.

**Non-Goals:**

- Approval workflow for permission changes.
- Periodic access-review campaigns or recertification.
- SSO/SCIM, multi-tenant organizations, or arbitrary ABAC expressions.
- Column-level masking or database-side row-level security.
- AgentScope sandbox or container isolation (separate concern; see the exploration discussion).
- A general-purpose authorization framework; this is a focused RBAC + data-scope model for this product.

## Decisions

### 1. Data model: access-control tables in the application database

Add the following tables to the app database (names are illustrative; follow the existing `snake_case` schema style):

| Table | Purpose | Key columns |
| --- | --- | --- |
| `access_role` | Role catalog | `id`, `code`, `name`, `description`, `parent_id`, `built_in`, `enabled`, timestamps |
| `access_permission` | Permission catalog | `id`, `permission_key`, `module`, `action`, `description`, `built_in` |
| `access_role_permission` | Role → permission | `role_id`, `permission_id` |
| `access_user_role` | User → role | `user_id`, `role_id`, `created_at` |
| `access_role_datasource` | Role → datasource | `role_id`, `datasource_id` |
| `access_data_scope` | Per-role/table data policy | `role_id`, `datasource_id`, `table_name`, `policy`, `column_name`, `principal_attribute` |
| `access_audit` | Change log | `id`, `actor_id`, `action`, `entity_type`, `entity_id`, `before_json`, `after_json`, `result`, `reason`, `created_at` |

`user_scope` remains the source of principal attributes (`class_id`, `student_id`, `student_no`). Add `user_id` as a supported attribute if needed; do not create a second attribute table in this phase.

`access_role.code` is immutable after creation and is used only for display/diagnostics; enforcement never branches on it. Built-in roles are seeded rows with `built_in = true`. The built-in administrator role cannot be deleted or disabled; its management capability is protected by the anti-escalation rules.

`access_data_scope` allows one policy per role/datasource/table in this phase. `FILTER` has exactly one column and one principal attribute; `ALL` and `DENY` have neither. This deliberately avoids an expression language and arbitrary SQL in the admin UI.

Alternative considered: storing policies as JSON expressions. Rejected because it creates an injection surface, is hard to validate, and is difficult to explain in the admin UI.

### 1b. Physical schema (MySQL 8 / H2 MySQL mode)

`app_user` gains two columns (keep the legacy `role` column for display/backfill):

```sql
ALTER TABLE app_user ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE app_user ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
```

New tables. No database-level foreign keys: the existing schema has none, H2 tests stay portable, and every reference is validated in the service layer; roles/users are disabled rather than deleted, so orphans cannot appear.

```sql
CREATE TABLE IF NOT EXISTS access_role (
    id          VARCHAR(64)  PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,          -- immutable after creation
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(512),
    parent_id   VARCHAR(64),                     -- single-parent inheritance, cycle checked in app
    built_in    BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  BIGINT       NOT NULL,
    updated_at  BIGINT       NOT NULL,
    CONSTRAINT uk_access_role_code UNIQUE (code)
);
CREATE INDEX idx_access_role_parent ON access_role (parent_id);

CREATE TABLE IF NOT EXISTS access_permission (
    id              VARCHAR(64)  PRIMARY KEY,
    permission_key  VARCHAR(128) NOT NULL,       -- e.g. chat.use, user.manage
    module          VARCHAR(64)  NOT NULL,
    action          VARCHAR(64)  NOT NULL,
    description     VARCHAR(512),
    built_in        BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_access_permission_key UNIQUE (permission_key)
);

CREATE TABLE IF NOT EXISTS access_role_permission (
    role_id       VARCHAR(64) NOT NULL,
    permission_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX idx_access_role_permission_perm ON access_role_permission (permission_id);

CREATE TABLE IF NOT EXISTS access_user_role (
    user_id    VARCHAR(64) NOT NULL,
    role_id    VARCHAR(64) NOT NULL,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_access_user_role_role ON access_user_role (role_id);

CREATE TABLE IF NOT EXISTS access_role_datasource (
    role_id       VARCHAR(64) NOT NULL,
    datasource_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id, datasource_id)
);
CREATE INDEX idx_access_role_datasource_ds ON access_role_datasource (datasource_id);

CREATE TABLE IF NOT EXISTS access_data_scope (
    id                  VARCHAR(64)  PRIMARY KEY,
    role_id             VARCHAR(64)  NOT NULL,
    datasource_id       VARCHAR(64)  NOT NULL,
    table_name          VARCHAR(128) NOT NULL,
    policy              VARCHAR(16)  NOT NULL,   -- ALL | FILTER | DENY
    column_name         VARCHAR(128),            -- required for FILTER, null otherwise
    principal_attribute VARCHAR(32),             -- userId | username | studentId | studentNo | classIds
    created_at          BIGINT       NOT NULL,
    updated_at          BIGINT       NOT NULL,
    CONSTRAINT uk_access_data_scope UNIQUE (role_id, datasource_id, table_name)
);
CREATE INDEX idx_access_data_scope_lookup ON access_data_scope (datasource_id, table_name);

CREATE TABLE IF NOT EXISTS access_audit (
    id          VARCHAR(64)  PRIMARY KEY,
    actor_id    VARCHAR(64)  NOT NULL,
    action      VARCHAR(64)  NOT NULL,           -- role.create, user.disable, password.reset, ...
    entity_type VARCHAR(32)  NOT NULL,           -- ROLE | PERMISSION | USER | GRANT | DATA_SCOPE
    entity_id   VARCHAR(64),
    before_json TEXT,
    after_json  TEXT,                            -- never password/hash/token material
    result      VARCHAR(16)  NOT NULL,           -- SUCCESS | DENIED | FAILED
    reason      VARCHAR(512),
    created_at  BIGINT       NOT NULL
);
CREATE INDEX idx_access_audit_created ON access_audit (created_at);
CREATE INDEX idx_access_audit_entity ON access_audit (entity_type, entity_id);
CREATE INDEX idx_access_audit_actor ON access_audit (actor_id);
```

Seed matrix (equivalent to the current `TablePolicy`):

| Role | Permissions | Datasource | Data scopes |
| --- | --- | --- | --- |
| `admin` (built-in) | all, including `access.manage`, `user.read`, `user.manage`, `audit.read`, `access.bypass_data_scope` | none needed (bypass) | none needed (bypass → `ALL`) |
| `student` | `chat.use`, `datasource.read` | `campus` | `class` → FILTER `id IN principal.classIds`; `student` → FILTER `student_no = principal.studentNo`; `score` → FILTER `student_id = principal.studentId`; `attendance` → FILTER `student_id = principal.studentId`; `course` → ALL |
| `counselor` | `chat.use`, `datasource.read` | `campus` | `class` → FILTER `id IN principal.classIds`; `student` / `score` / `attendance` → FILTER `class_id IN principal.classIds`; `course` → ALL |

`*_json` columns are `TEXT` rather than MySQL `JSON` so the same DDL runs on H2 in tests. Timestamps are epoch-millis `BIGINT` like the rest of the schema. The migration script adds columns/indexes only when absent (information_schema guard), because MySQL has no `ADD COLUMN IF NOT EXISTS`.

### 2. Effective permissions: resolve from the database, not the JWT claim

Introduce an `EffectiveAccess` (or `AccessContext`) value object containing:

- the user id and effective role list;
- the union of permissions from all enabled roles and their ancestors;
- the effective datasource grants;
- the effective per-table data-scope policies;
- the principal attributes resolved from `user_scope`.

`DataScopeResolver` becomes `AccessContextResolver`: it takes the authenticated user id, loads roles/permissions/scopes, and fails closed when no usable policy exists. The JWT `role` claim remains for display and backward compatibility, but it is not the enforcement source. This means a role/permission change takes effect on the next request without forcing a re-login.

No cache in the first implementation. A per-request load is a few indexed queries; if profiling shows a problem, add a short-TTL cache with explicit invalidation on admin writes. Alternative considered: putting all roles in the JWT. Rejected because role changes would require re-login and the token would grow unboundedly.

### 3. Data-scope semantics

For each user and table, compute the effective policy from all enabled roles:

1. If any role has `DENY` for the table → the table is denied.
2. Otherwise, if any role has `ALL` → the table is unrestricted.
3. Otherwise, all `FILTER` policies are combined with `OR` (the union of each role's allowed rows).
4. If no role defines a policy for the table → deny by default.

Within one role, one policy per table. `FILTER` supports:

- `column = principal.<attribute>` for a single-valued attribute (`userId`, `username`, `studentId`, `studentNo`);
- `column IN principal.<attribute>` for a set-valued attribute (`classIds`).

Column names and principal attributes are validated against the datasource metadata and a fixed attribute catalog. Identifiers are quoted; values are parameterized or safely quoted. No raw predicate text is accepted.

The built-in administrator role receives the `access.bypass_data_scope` permission, which makes the policy engine return `ALL` for every table. That is a permission, not a role-name check; an administrator could grant it to another role deliberately.

Alternative considered: a role-name `admin` bypass. Rejected because it reintroduces the hardcoding this change removes and makes the admin role impossible to model as data.

### 4. Enforcement chokepoint

Replace `TablePolicy` with a policy engine that consumes `EffectiveAccess`:

- `DataScopeRewriter` takes `EffectiveAccess` instead of `DataScope` and asks the engine for each table's policy.
- `SqlTools`, `Text2SqlPipelineService`, and the schema/introspection helpers use the same `EffectiveAccess`.
- `SqlTools` still injects the access context through AgentScope's `RuntimeContext`; the model cannot supply it via `@ToolParam`.
- `scopedExplanation` and `callerContext` use role display names and policy descriptions from the context, not `isStudent()` / `isCounselor()`.
- `SecurityConfig` and controllers check permissions (for example, an `AccessPermissionService` or method-level checks) rather than `Roles.isAdmin(jwt)`.

The chokepoint remains “validate → rewrite → validate → execute” in the pipeline. The agent path continues to call the pipeline through `query_database`, so both paths share the same enforcement.

### 5. CTE-safe rewriting

The current `DataScopeRewriter` adds all CTE names to a set before rewriting the CTE bodies, and `rewriteTable` returns early when a name is in that set. A CTE named like a protected table can therefore skip the whitelist and filter.

Fix approach:

- While rewriting a non-recursive CTE body, the CTE's own name is **not** visible; only previously defined CTEs are visible.
- Reject any CTE whose name collides with a protected table name, even if the body looks benign. This is a simple, testable, fail-closed rule.
- Reject `WITH RECURSIVE` unless a later change adds explicit recursive support; recursive visibility is easy to get wrong and not needed for this product.
- Keep the existing “parse failure → reject” behavior.
- Add tests for `WITH student AS (SELECT * FROM student) SELECT * FROM student`, schema-qualified CTE bodies, nested CTEs, and a legal CTE that references a protected table in its body.

Alternative considered: fully modeling SQL CTE scoping. Rejected for this phase because the product does not need recursive CTEs and the fail-closed rule is safer.

### 6. Admin API and UI

Add an admin-only area at `/admin/access-control` with permission `access.manage`. Tabs:

1. **Roles** — list/create/edit/disable roles, set a parent role, show built-in status.
2. **Permissions** — permission catalog grouped by module, with a role-permission matrix.
3. **Data scope** — per role, per datasource, per table: `ALL` / `FILTER` / `DENY`; for `FILTER`, select a column and a principal attribute.
4. **Users** — search/paginate users; create a user (username, display name, initial password, roles); edit the display name; disable/enable; reset password; assign/remove roles. Password fields are write-only and never echoed back.
5. **Datasource access** — reuse the existing role → datasource matrix, migrated to role records.
6. **Audit** — read-only change log with actor, time, entity, before/after, result.

Backend endpoints live under `/api/admin/access-control/**`; they validate role codes, permission keys, table/column names, principal attributes, inheritance cycles, last-admin protection, username uniqueness and password strength. The frontend route guard and navigation use the effective permission set, but the API is the security boundary. User-management actions require `user.manage` (list/detail: `user.read`), so an operator can be allowed to manage accounts without being able to edit roles or data scopes.

Alternative considered: extending the existing datasource-access page only. Rejected because roles, permissions, data scopes, users, and audit do not fit one matrix.

### 6b. User lifecycle and session invalidation

Add two columns to `app_user`:

- `enabled BOOLEAN NOT NULL DEFAULT TRUE` — login rejects disabled accounts; the resolver also rejects them on every request.
- `token_version INTEGER NOT NULL DEFAULT 0` — included in the JWT as `ver`; the resolver compares it with the database. Disable, re-enable and password reset bump the version.

"Hard delete" is not exposed: disabling preserves conversations, messages and audit rows. Creation uses the existing BCrypt encoder and a minimum length; list/detail responses never include the hash. Self-registration continues to create a `student` account, and it also inserts the seeded `student` role into `access_user_role` so the new model covers accounts created after the migration.

Alternative considered: a token denylist. Rejected because it adds a second store and cleanup job; the version column gives the same "invalidate on change" behaviour with one indexed read already needed by the resolver. Alternative considered: forcing re-login on every role change. Rejected because role changes are already picked up per request; only account status and credentials need invalidation.

### 7. Anti-escalation and lockout protection

- Only `access.manage` can call role/permission/data-scope management endpoints; `user.read` / `user.manage` gate the user APIs.
- A user cannot grant themselves a permission they do not currently hold; the built-in administrator is the bootstrap exception. The same rule applies when creating a user or assigning roles: the actor cannot grant a role whose effective permissions exceed their own.
- The last principal holding `access.manage` cannot be removed, disabled or have their admin role revoked.
- The built-in administrator role cannot be deleted or disabled.
- Role codes are immutable; permission keys come from the seeded catalog; built-in permission definitions cannot be deleted.
- An administrator cannot disable their own account while they are the last holder of `access.manage`; disabling any other account is audited.
- Every rejected escalation attempt is recorded in the audit log with result `DENIED`.

### 8. Audit

Write an audit row in the same transaction as the access-control change. Record actor, action, entity type/id, before/after JSON, and result. Cover role/permission/data-scope changes, datasource grants, and user lifecycle events (create, disable, enable, password reset, role assignment). Never write a password, hash or full token into the audit payload. Audit reads require `audit.read`. Audit rows are append-only through the API; no update/delete endpoint. Retention/export is a later operational concern.

### 9. Migration and compatibility

- Add the new tables to `db/app-schema.sql` with `CREATE TABLE IF NOT EXISTS` and indexes.
- Add `enabled BOOLEAN NOT NULL DEFAULT TRUE` and `token_version INTEGER NOT NULL DEFAULT 0` to `app_user`; backfill existing rows with the defaults. Do not drop `app_user.role`.
- Seed built-in roles, permissions (`user.read`, `user.manage` included), role-permission mappings, role-datasource grants, and data-scope policies in `db/app-data.sql` with `INSERT IGNORE`.
- Add a one-time migration script under `db/migration/` for existing deployments. It:
  - creates the tables if absent and adds the two `app_user` columns if absent;
  - inserts built-in roles/permissions;
  - backfills `access_user_role` from `app_user.role`;
  - backfills `access_role_datasource` from `datasource_access`;
  - backfills `access_data_scope` from the current `TablePolicy` behavior (`student` / `counselor` / `admin`);
  - leaves `app_user.role`, `user_scope`, and `datasource_access` in place for compatibility.
- The migration is idempotent; running it twice is safe.
- Existing users are migrated with `enabled = TRUE` and `token_version = 0`; an operator can then disable accounts without deleting them.
- JWTs issued before this deployment do not carry `ver`. The resolver rejects them (fail closed), so every user signs in once after the upgrade; this is expected and should be stated in the release notes.
- New self-registered users are written to `access_user_role` with the seeded `student` role at registration time, so they work under the new model without a separate backfill.
- The demo deployment currently recreates the app DB only on an empty data directory. The deploy docs must state that an existing deployment needs the migration script (or a fresh `down -v` with data loss) before the new code starts.
- Legacy role values that cannot be mapped are left without an `access_user_role`; the resolver denies them rather than defaulting to a scoped role.

### 10. Testing strategy

- Unit: effective permission union and inheritance; cycle rejection; data-scope merge (`DENY` > `ALL` > `FILTER` union); unknown table/column/attribute; one-policy-per-table validation; anti-escalation; last-admin protection; user creation validation (unique username, password strength); password reset/token-version bump.
- Authentication: disabled account cannot log in; an existing token is rejected after disable and after password reset; re-enabled account still needs a new login; self-registration assigns the `student` role.
- SQL rewriting: CTE collision, CTE body references, nested CTEs, recursive rejection, parse failure, backslash/quote handling in predicates.
- Integration: admin API 403/409/422 behavior; user create/disable/enable/reset/role-assignment; audit rows (no passwords); pipeline and agent path produce the same filtered result; role change takes effect on the next request.
- Migration: run the backfill against a copy of the seed data and compare effective access for `admin`, `student01`, and `counselor01` with the current behavior.
- Frontend: `npm run build`; manual permission-aware navigation, user creation/disable/reset, and admin CRUD checks. No new component-test framework in this change.

## Risks / Trade-offs

- [Administrator lockout during migration or role edits] → Seed a built-in admin role with `access.manage` and `access.bypass_data_scope`; protect the last holder; run the migration in a transaction and verify the admin login before enabling the UI.
- [Policy engine becomes a new single point of failure] → Keep it pure and unit-tested; default deny; log the effective policy decision for denied requests.
- [Per-request role/permission queries add latency] → Start without a cache; add indexes on `access_user_role(user_id)`, `access_role_permission(role_id)`, and `access_data_scope(role_id, datasource_id, table_name)`; add a short-TTL cache only if measurements require it.
- [Admin UI accidentally allows an unsafe predicate] → Typed policies only; table/column/attribute selectors; server-side validation against metadata; no raw SQL field.
- [Data-scope inheritance/merge semantics surprise users] → Document `DENY` > `ALL` > `FILTER` union; show the effective policy preview in the admin UI.
- [Spec conflict with the completed-but-unarchived `campus-data-permission` change] → Archive that change first and update its “only three roles” requirement, or merge the two changes before archiving this one.
- [Migration on an existing MySQL volume] → Provide an idempotent migration script; document backup and verification; do not rely on init scripts for existing volumes.
- [Recursive CTEs or exotic SQL remain a bypass surface] → Reject recursive CTEs in this phase; keep parse-failure fail-closed; add regression tests.
- [JWT still carries a stale `role` claim used elsewhere] → Treat the claim as display-only; audit any remaining `Roles.isAdmin` call sites and replace them with permission checks.
- [A disabled user keeps using an unexpired token] → Every request checks `enabled` and `token_version` in the resolver; disable/reset bump the version, so the next request fails closed.
- [Password material leaks into logs, audit or API responses] → Password fields are write-only; list/detail DTOs omit them; audit stores only `{disabled: true}` / `{passwordReset: true}` markers.
- [An administrator locks the team out through user management] → The last `access.manage` holder cannot be disabled, and self-disable is rejected for that holder; the built-in admin role is protected.

## Migration Plan

1. Add the new tables, the two `app_user` columns, and seed the built-in roles/permissions/scopes.
2. Backfill existing users, datasource grants, and data scopes; verify `admin`, `student01`, `counselor01` effective access and that existing tokens still work with `token_version = 0`.
3. Deploy the backend with the new resolver/policy engine and the account-status/token-version check; keep legacy tables and claims for rollback.
4. Deploy the admin UI (including the Users tab) and permission-aware navigation.
5. Verify the CTE regression tests, the pipeline/agent parity tests, and the user lifecycle E2E (create → assign role → login → disable → next request rejected → reset password → old token rejected).
6. Rollback: revert the application image; the new tables and columns are additive and can remain unused. The legacy `app_user.role`, `user_scope`, and `datasource_access` remain the fallback.
7. Before archiving this change, confirm the `data-permission` main spec reflects the configurable model (the `campus-data-permission` change is already archived).

## Open Questions

- Approval workflow and periodic access review are deferred; they can be added as a follow-up change without changing this change's data model.
- Column-level masking is deferred; the policy model can later add a `column` dimension without changing role/permission records.
- Whether to add a short-TTL effective-permission cache is an operational decision after profiling; it does not change the specs or task breakdown.
