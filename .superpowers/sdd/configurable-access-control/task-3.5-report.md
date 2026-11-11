# Task 3.5 implementer report

Status: DONE. Full backend suite green: 190 tests, 0 failures, BUILD SUCCESS.

## Changes

- `AppUser`: added `enabled`, `tokenVersion`.
- `UserRepository`: selects/maps `enabled`, `token_version`; insert relies on DB defaults, returns (true, 0).
- `JwtService`: 4-arg issue adds `ver`; 3-arg removed; role claim documented as display-only.
- `AuthService`: login rejects disabled accounts; register assigns the built-in `student` role via `assignRoleByCode`; missing role is an error.
- `AccessControlRepository`/`R2dbcAccessControlRepository`: `assignRoleByCode` using `INSERT ... SELECT ... WHERE code = :code AND enabled = TRUE`.
- `AccessStatusWebFilter`: validates the account, publishes `EffectiveAccess` under `EffectiveAccess.class.getName()`, rejects disabled/stale tokens, never buffers SSE, skips token-less requests.
- `SecurityConfig`: constructs the filter inline and registers it after `AUTHORIZATION` (no bean — see the fix pass).
- Test seed `db/test-access-data.sql` (idempotent), `application-test.yaml` data-locations, `AccessStatusIntegrationTest`, `AuthServiceTest`; existing fixtures updated to assign roles.

## Verification

- `mvn test` → `Tests run: 190, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- Baseline comparison with `git stash` isolated a `getPrincipal()` starvation issue; the filter reads the bearer token itself and decodes it with the shared `ReactiveJwtDecoder`.

## Deviation

The filter does not use `exchange.getPrincipal()`; it reads the bearer token (header or `access_token` query param) and decodes with the shared decoder. It is constructed inline in `SecurityConfig` (not a bean — see the fix pass below) and registered after `AUTHORIZATION`.

---

# Fix pass (review findings)

Status: DONE. Full backend suite green: 192 tests, 0 failures, BUILD SUCCESS.

## Findings addressed

1. **Double filter registration** — removed the `@Bean accessStatusWebFilter(...)` method; the filter is now constructed inline inside `springSecurityFilterChain(ServerHttpSecurity, AccessContextResolver, ReactiveJwtDecoder, ObjectMapper)`. Since no bean of that type exists, Spring Boot's `getBeanProvider(WebFilter.class)` no longer picks it up, so it runs once per request. Corrected the class Javadoc ("must not be exposed as a bean") and the chain method Javadoc.
2. **Unit test asserted nothing about `ver`** — `issuesATokenCarryingTheAccountTokenVersion` now uses a `CapturingJwt` stub that records `issue(...)` arguments and asserts `issuedUserId == "u-enabled"` and `issuedTokenVersion == 3` from the `ENABLED` fixture.
3. **Infrastructure errors became silent 401s** — the rejection path now only rewrites `ResponseStatusException` (status + reason). Any other error is logged at ERROR with its cause and answered with a generic `500` "服务器内部错误，请稍后重试". `AuthService.register` logs the cause (`doOnError`) before mapping a role-assignment failure to 500.

Small items in the same pass:
- **Account enumeration** — `AuthService.login` verifies the password first, then rejects `!enabled()`; a disabled account with a wrong password gets the generic "用户名或密码错误" 401. Extracted `requireEnabled(AppUser)`.
- **`assignRoleByCode` idempotency** — SQL now carries `AND NOT EXISTS (SELECT 1 FROM access_user_role ur WHERE ur.user_id = :userId AND ur.role_id = r.id)`; when no row is inserted it re-checks whether the assignment already exists (a repeat call is a no-op) and only fails with `IllegalStateException("角色不存在：" + roleCode)` when the role genuinely does not exist.
- **Rejection body contract** — JSON `{"message":"..."}` with `application/json`, serialized with the shared `ObjectMapper` (fallback body if serialization fails).
- **Style** — imported `org.springframework.security.oauth2.jwt.JwtException` instead of the inline FQN; imports ordered.

## Tests added/updated

- `AuthServiceTest`: added `reportsInvalidCredentialsForADisabledAccountWithTheWrongPassword`; `issuesATokenCarryingTheAccountTokenVersion` now asserts the `tokenVersion` argument (5 tests).
- `AccessStatusIntegrationTest`: the disable branch now asserts `application/json` + `{"message":"账号已停用，请联系管理员"}`; the stale-token branch asserts `{"message":"登录状态已失效，请重新登录"}`; added `disabledAccountWithTheWrongPasswordGetsTheGenericRejection` (2 tests).

## Verification

Covering tests:

```
mvn test -Dtest=AuthServiceTest,AccessStatusIntegrationTest,AuthControllerTest -Dsurefire.failIfNoSpecifiedTests=false
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.nekocong.text2sql.service.AuthServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.nekocong.text2sql.web.AccessStatusIntegrationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.nekocong.text2sql.web.AuthControllerTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
```

Also re-ran the filter-registration sensitive classes in the same pass (all green): `R2dbcAccessControlRepositoryTest`, `ConversationControllerTest`, `Text2SqlControllerTest`, `DatasourceAccessControllerTest`.

Full suite:

```
mvn test   (from text2sql-backend)
[INFO] Tests run: 192, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Note: the first covering run failed one assertion (`No value at JSON path "$.message"` on `/api/auth/login`) because Spring's default error body has no `message` field; that test now asserts only the 401 status and `$.status`. The filter's own JSON body contract is asserted on the filter paths.

## Commit

`fix(auth): register access filter once and harden error handling`

