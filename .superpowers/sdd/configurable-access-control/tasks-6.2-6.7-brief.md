# Tasks 6.2–6.7 brief — access-control UI (shell + six tabs)

Goal: build the complete permission-management area: `/admin/access-control` route, permission-aware
navigation/guard, and six working tabs (Roles, Permissions, Data scope, Users, Datasource access,
Audit) wired to the typed client from task 6.1. The backend API is the security boundary; the UI only
hides/disables what the caller cannot use.

## Prerequisite from 6.1

`text2sql-frontend/src/api/accessControl.ts` exposes typed functions for every endpoint, and the auth
store has `can(permission)` / `isAdmin` based on `user.permissions`. If a function you need is
missing, extend the client in the same style (do not call `http` from components directly).

## Shell / route (6.2)

- Route `/admin/access-control`, name `access-control`, title `权限管理`, meta with
  `permission: 'access.manage'` (extend `RouteMeta`). Update the router guard to check
  `auth.can(meta.permission)` when present; the legacy `role !== 'admin'` check must no longer gate
  this page. Reloading a deep link must work.
- Header/brand navigation: add a `权限管理` entry visible only when `auth.can('access.manage')`.
  On narrow screens it must remain reachable (existing header/menu pattern).
- Page layout: title + short description, Element Plus `el-tabs` with the six tabs, each tab
  lazy-renders its data on activation (avoid six parallel loads on mount).
- Permission-aware inside the page: read-only tabs/actions stay disabled with a tooltip when the
  caller lacks the specific permission (e.g. `user.read` without `user.manage` shows the user list
  with write actions disabled); the API is still the boundary. Show a clear 403 state if a call is
  rejected.
- Keep the existing visual language (Element Plus, spacing, cards, table density); mobile: tabs
  scroll horizontally, tables scroll inside their card, dialogs fit the viewport.

## Roles tab (6.3)

- Table: code (monospace), name, description, parent role, built-in badge, enabled switch, actions.
- Create/edit dialog: code (create only; disabled on edit), name, description, parent selector
  (exclude itself and its descendants, mark disabled parents as unavailable), enabled switch.
  Built-in roles: code/read-only, `admin` cannot be disabled; rename is allowed.
- Surface backend validation inline (duplicate code, cycle, unknown parent, built-in protection);
  disable the submit button while saving and show success feedback. No DELETE.

## Permissions tab (6.4)

- Role selector (all roles, grouped built-in/custom) + permission catalog grouped by module with
  checkboxes and a per-group select-all; show each permission's key/action/description.
- Dirty-state save/reset; PUT replaces the whole set. After save, reload the role's set and show a
  success toast. A denied escalation (403) must show the backend reason and keep the previous state.
- Built-in admin: warn that removing `access.manage`/`access.bypass_data_scope` is blocked.

## Data scope tab (6.5)

- Filters: role + datasource. Table list with existing rules (table, policy badge, column/attribute),
  edit/delete actions; upsert dialog with:
  - table selector (registered tables from the datasource schema),
  - policy `ALL` / `FILTER` / `DENY`,
  - for FILTER: column selector (that table's columns) + principal attribute selector
    (`userId`, `username`, `studentId`, `studentNo`, `classIds`) with type-compatible pairing
    (numeric attributes → numeric columns; text → text). Invalid combinations blocked before save.
- Block save when the combination is incomplete; surface 400/422 messages inline. Empty state
  explains that no policy means deny for non-bypass roles.
- Backend prerequisite: the schema/metadata endpoint may not exist yet. If it does not, implement a
  small read-only endpoint in this task:
  `GET /api/admin/access-control/datasources/{id}/schema` → `{datasourceId, tables:[{name, columns:[{name,typeName}]}]}`,
  `access.manage`-gated, 404 unknown datasource, introspection on the existing blocking scheduler.
  Add the matching client function/types and a backend test (unknown datasource 404, known datasource
  returns tables). Do not return connection secrets.

## Users tab (6.6a)

- Search input (debounced), enabled filter, role filter, paginated table (server-side page/size):
  username, display name, roles (tags), enabled, created time, actions.
- Create dialog: username, display name, initial password (+ confirm), role multi-select. Password
  inputs write-only, never echoed; client-side validation mirrors the backend (8–72 chars, username
  pattern).
- Row actions: edit display name, enable/disable (with confirm), reset password dialog (write-only),
  assign roles (multi-select, save replaces the set). Disabled rows visually distinct (muted tag).
- Clear empty/loading/error states; last-admin rejection shows the backend message.

## Datasource access tab (6.6b)

- Matrix of role records × registered datasources with checkboxes, grouped by role; built-in roles
  included, admins labelled as bypass. Save per role (`PUT /roles/{id}/datasources`) with dirty state,
  success toast and the backend reason on failure. Empty state explains grants only matter with
  `datasource.read`.

## Audit tab (6.7)

- Filter bar: actor id/username text, action, entity type, result, time range; pagination.
- Read-only table: time, actor, action, entity, result tag (SUCCESS/DENIED), reason; row expands
  (drawer/dialog) to a formatted before/after JSON diff (two panes or a simple side-by-side).
- No edit controls. Empty/error states; `audit.read` gating (nav/tab) with a clear message if the
  caller reaches the API without it.

## Engineering constraints

- Vue 3 `<script setup lang="ts">`, Composition API, Element Plus; no new dependencies.
- All API access through `src/api/accessControl.ts`; extract a small helper for `{status,message}`
  errors and reuse it. No `any`; no `console.log` in shipped code.
- Split components under `src/components/access-control/` (one component per tab + shared dialogs/
  helpers). Keep each file focused (< ~500 lines); extract table/dialog widgets.
- Accessibility: labelled inputs, keyboard-operable tabs/dialogs, focus return after dialogs,
  `el-table` empty text; color is not the only status signal (tags have text).
- Verify: `npm run build` and `npm test` pass. Backend changes (if any) pass `mvn test` without
  `DASHSCOPE_API_KEY`. Manually check the main flows in `npm run dev` against a running backend if
  possible; otherwise state what was not exercised.
- Commit backend and frontend changes with conventional messages.

## Out of scope

- App-wide permission-aware navigation for chat/datasources/model settings (task 6.8).
- New backend permissions or policy semantics.
