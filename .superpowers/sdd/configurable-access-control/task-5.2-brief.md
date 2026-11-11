# Task 5.2 brief — permission catalog + role-permission matrix

Goal: expose the seeded permission catalog and let an administrator edit one role's permission set, with
audit and next-request effect. Extend the existing access-control admin slice; do not build data
scopes or user management.

## API (base `/api/admin/access-control`, already `access.manage`-gated)

- `GET /permissions` → `AccessPermissionView[]`, ordered by module then key:
  ```json
  { "id": "...", "key": "chat.use", "module": "chat", "action": "use",
    "description": "...", "builtIn": true }
  ```
- `GET /roles/{id}/permissions` → `{ "roleId": "...", "permissions": ["chat.use", "datasource.read"] }`
  (keys, ordered).
- `PUT /roles/{id}/permissions` body `{ "permissions": ["chat.use", "..."] }` → the same shape.
  - role must exist → 404 "角色不存在";
  - every key must exist in the catalog → 400 "未知权限：<key>";
  - duplicate keys are de-duplicated;
  - replace the role's set atomically and write one `access_audit` row (`action = role.permissions`,
    `entity_type = ROLE`, `entity_id = roleId`, before/after = sorted key arrays, result SUCCESS);
  - a rejected request writes a `DENIED` row with the reason;
  - an empty array is allowed (clears the role's permissions).
- Do not add role-permission endpoints to `AccessControlRepository`; use
  `AccessAdminRepository`/`AccessAdminService` (extend them).

## Notes

- Add an `AccessPermission` domain record (`id, key, module, action, description, builtIn`) and map the
  catalog; do not change `EffectiveAccess` or the resolver.
- Permissions are seeded by `db/app-data.sql` / `test-access-data.sql`; no new permission keys.
- The actor id comes from the JWT subject (same pattern as 5.1). Anti-escalation (cannot grant a
  permission the actor lacks) is task 5.7; do not implement it here, but leave a clear seam (a
  service method that can consult the actor's `EffectiveAccess`).
- Built-in roles: editing the permission set is allowed; the last-`access.manage` lockout rule is 5.7.
- Reuse the 5.1 transaction pattern (`TransactionalOperator`) so the set replacement and the audit
  row commit together; `DENIED` rows use the existing `REQUIRES_NEW` helper.

## Tests

`web/AccessControlPermissionTest` (`@SpringBootTest`, RANDOM_PORT):
- catalog returns the nine seeded keys with module/action/builtIn;
- get a role's permissions; replace with a subset → 200 and the next GET reflects it;
- empty array clears;
- unknown role → 404; unknown key → 400;
- non-admin (student) → 403;
- audit: one SUCCESS row per replace (before/after sorted) and one DENIED row for unknown key;
- next-request effect: assign the edited role to a fresh user, resolve via `AccessContextResolver`
  (or call an endpoint gated by the newly granted `datasource.write`) and assert the permission is
  present immediately.

## Constraints

- Java 25 / Spring Boot 4.1 WebFlux; no new dependencies.
- Fail closed; bind all values; no raw SQL from the client.
- Do not touch SecurityConfig, the policy engine, the pipeline, data scopes, users, or the schema.
- `mvn test` without `DASHSCOPE_API_KEY`; report BUILD SUCCESS + count. Commit.
