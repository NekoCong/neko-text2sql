# Task 3.4 implementer report

Status: DONE. Full backend suite green: 235 tests, 0 failures, BUILD SUCCESS (no DASHSCOPE_API_KEY).

## Changes

- `security/PermissionAuthorizationManager` (new): `ReactiveAuthorizationManager<AuthorizationContext>` constructed with one permission key; reads `EffectiveAccess` from the exchange attributes; denies when auth/access/permission is absent. Spring Security 7.1 uses `authorize(Mono<Authentication>, T)` returning `Mono<AuthorizationResult>` (the old `check` signature no longer exists), so it implements `authorize` and returns `AuthorizationDecision`.
- `config/SecurityConfig`: `AccessStatusWebFilter` moved to `addFilterAfter(..., AUTHENTICATION)` so the attribute is present before `AUTHORIZATION`; `hasRole("ADMIN")` replaced by `.access(permission(...))`: `/api/admin/**` and `/api/text2sql/knowledge/**` → `access.manage`; POST/PUT/DELETE `/api/text2sql/datasources/**` → `datasource.write`. Public paths, `knowledge/legacy/**` authenticated, and `anyExchange().authenticated()` unchanged. Javadocs updated.
- `controller/DatasourceAccessController`: Javadoc only (no `Roles.isAdmin` existed in the datasource/model controllers).
- `web/PermissionAuthorizationTest` (new): custom role with only `access.manage` → 200 on `/api/admin/datasource-access`; legacy `admin` claim with only the `student` access role → 403; `datasource.write` role reaches the datasource controller (404 on unknown id) while a `chat.use`-only role gets 403.

## Verification

- `mvn -o test -Dtest=PermissionAuthorizationTest,DatasourceAccessControllerTest` → 8 tests, 0 failures.
- Full `mvn -o test` (no key) → 235 tests, 0 failures, BUILD SUCCESS.

## Concerns

- `MODEL_MANAGE` remains ungated because `/api/model-configs/**` is per-user config, not under `/api/admin/**`; the brief did not map a model route. Follow-up decision.
- Requests without a bearer token pass the status filter and are rejected by the security entry point before authorization; the permission manager also denies when the attribute is absent.
- PowerShell reports exit code 1 on Maven stderr warnings; results read from Maven/surefire output.
