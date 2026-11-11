# Task 5.1 implementer report

Status: DONE. Full backend suite green: 266 tests, 0 failures, BUILD SUCCESS (no DASHSCOPE_API_KEY).

## Changes

- `access/api/dto/AccessRoleRequest`, `AccessRoleView`.
- `access/api/AccessControlController`: `/api/admin/access-control/roles` GET/POST/PUT; JWT subject as actor.
- `access/AccessAdminRepository` + `R2dbcAccessAdminRepository`: role list/find/by-code/insert/update + audit insert (bound parameters, app UUIDs).
- `access/AccessAdminService`: code regex `^[a-z][a-z0-9_-]{1,63}$`, name ≤100, duplicate code 409, immutable code 409, parent exists 404, cycle 409, built-in admin cannot be disabled 409; audit row per mutation (SUCCESS or DENIED with reason; audit failure logged, not fatal because no R2DBC transaction manager exists).
- `controller/ApiExceptionHandler`: added a `ResponseStatusException` handler returning `{status,message}` so the API surfaces the service's exact 400/404/409 messages.
- `web/AccessControlRoleTest`: 11 tests (create/list, duplicate, update + code change, self/two-role cycle, unknown parent/role, validation, disable custom vs built-in admin, built-in rename, student 403, audit counts).

## Verification

- `mvn test` (no key) → 266 tests, 0 failures, BUILD SUCCESS.

## Concerns

- No transaction: role write and audit row are separate statements; a failed audit insert leaves the mutation standing (logged, documented).
- `ApiExceptionHandler` change is outside the listed files but needed for exact messages; existing tests stay green.
- `parentId: null` on update clears the parent; `name`/`description`/`enabled` null means keep current.
- Create validation failures before an entity exists write no audit row.
- Controller reads the actor from `ReactiveSecurityContextHolder`, not the exchange attribute.
