# Task 3.4 brief — permission-based route authorization

Goal: replace the JWT-role route checks (`hasRole("ADMIN")`) with effective-permission checks resolved
from the access-control tables, so a custom role can be granted management capability without being
named `admin`, and a demoted admin loses it on the next request.

## Context

- `AccessStatusWebFilter` resolves `EffectiveAccess` and stores it under
  `EffectiveAccess.class.getName()` in the exchange attributes. It is currently registered **after**
  `AUTHORIZATION`, which would make the attribute unavailable to an authorization manager.
- `Permissions` holds the keys (`access.manage`, `datasource.write`, `model.manage`, ...).
- The seeded admin role has every permission; student/counselor have `chat.use` + `datasource.read`.

## Required changes

1. New `security/PermissionAuthorizationManager implements ReactiveAuthorizationManager<AuthorizationContext>`:
   - constructed with one permission key;
   - `check(Mono<Authentication>, AuthorizationContext)` reads
     `context.getExchange().getAttributes().get(EffectiveAccess.class.getName())`;
   - absent authentication or absent access → `AuthorizationDecision(false)` (fail closed);
   - otherwise `access.hasPermission(key)`.
   - Add a static factory `permission(String key)` in `SecurityConfig` or the manager.
2. `SecurityConfig`:
   - register `AccessStatusWebFilter` **before** `AUTHORIZATION` (e.g.
     `addFilterAfter(accessStatus, SecurityWebFiltersOrder.AUTHENTICATION)`) so the attribute exists;
   - replace `hasRole("ADMIN")`:
     * `/api/admin/**` → `access.manage`;
     * `/api/text2sql/knowledge/**` → `access.manage` (legacy stays `.authenticated()`);
     * `POST/PUT/DELETE /api/text2sql/datasources/**` → `datasource.write`.
   - Keep public paths and `.anyExchange().authenticated()` unchanged.
   - Update the Javadoc that still says admin routes require `ROLE_ADMIN`.
3. Controllers: `DatasourceAccessController`, `DataSourceController`, `ModelConfigController` do not
   call `Roles.isAdmin`; no controller change is needed beyond confirming that. Leave the
   conversation-ownership `Roles.isAdmin` calls in `ConversationController`/`Text2SqlController`
   alone (out of this task's scope).
4. Tests:
   - Keep `DatasourceAccessControllerTest` green (student 403, admin 200).
   - Add `web/PermissionAuthorizationTest` (SpringBootTest, RANDOM_PORT):
     * create a custom role with only `access.manage` (no `admin` code), a user assigned that role,
       and a token; `GET /api/admin/datasource-access` → 200 (proves permission, not role name);
     * create a user whose legacy `app_user.role` is `admin` but whose access role is the seeded
       `student` (no `access.manage`), and a token; the same endpoint → 403 (the JWT claim must not
       grant access);
     * a custom role with only `datasource.write` can `PUT`/`DELETE` a datasource (or at least
       reaches the controller) while a `chat.use`-only role gets 403.
   - Use the test seed (`test-access-data.sql`) and `AccessControlRepository` to create the custom
     rows; unique ids/usernames per run.

## Constraints

- Java 25 / Spring Boot 4.1; no new dependencies.
- Fail closed: no access attribute → denied, never fall back to the role claim.
- Do not change the access repository/schema, the pipeline enforcement, or CTE handling.
- Run `mvn test` without `DASHSCOPE_API_KEY`; report BUILD SUCCESS + count. Commit.
