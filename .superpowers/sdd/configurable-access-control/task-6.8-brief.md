# Task 6.8 brief — app-wide permission-aware navigation and guards

Goal: make every navigation entry and route guard use the effective permission set (from
`/api/auth/me`, refreshed on boot) instead of the legacy `role` claim. The API remains the security
boundary; this is UX, not authorization.

## Navigation

Hide/disable entries based on `auth.can(...)`:

| Entry / action | Permission |
| --- | --- |
| 对话 (`/`) | `chat.use` |
| 数据源 (`/datasources`) | `datasource.read` |
| 数据源新建/编辑/删除/测试 | `datasource.write` |
| 模型配置 (user dropdown `/settings/models`) | `model.manage` |
| 知识库 (`/knowledge`) | `access.manage` (backend currently gates this route) |
| 权限管理 (`/admin/access-control`) | `access.manage` |
| 旧数据源权限 (`/admin/datasource-access`) | hide the entry; keep/redirect the route for compatibility |

If a user lacks `chat.use`, land them on the first permitted page (e.g. datasources) or show a clear
"无可用功能" page; do not loop redirects.

## Route guards

- Extend `RouteMeta` with `permission?: string`; guard checks `auth.can(meta.permission)` after
  `auth.restore()` and, when a token exists, a profile refresh (see 6.1 review follow-up: stale
  localStorage permissions must not be trusted).
- Legacy `meta.admin` can remain as a deprecated alias, but new routes use `permission`.
- Direct URL access without the permission redirects to the fallback page (or 403 view), never renders
  the page. A direct API call still returns 403 from the backend — verify with one manual/HTTP check.
- On logout/token invalidation, clear the cached profile so the next login re-evaluates.

## Write actions

- Datasource page: hide/disable create/edit/delete/test-with-credentials controls without
  `datasource.write`; keep list/read with `datasource.read`.
- Model settings: route-guard `model.manage`; do not render the page without it.
- Chat: if `chat.use` is absent, hide the composer or show a read-only notice (backend still rejects).
- Knowledge/access-control: `access.manage` (already implemented for access-control in 6.2).

## Tests / verification

- Frontend unit tests for the permission→entry mapping and the fallback-page selection (pure helper).
- `npm run build` + `npm test` pass.
- Manual: for each of `admin`, `student01`, `counselor01`, verify the visible entries and one direct
  URL/API attempt (403). If the backend is not running, state what was verified statically.
- No backend behavior changes.

## Constraints

- Vue 3 + TypeScript + Element Plus; no new dependencies.
- Do not change API authorization, permissions, roles, or the access-control page internals.
- Commit with a conventional message.
