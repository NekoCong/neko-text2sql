# Task 3.5 brief — account status and session invalidation

Implement account-status checks and token invalidation for the configurable access-control change.

## Requirements

1. `AppUser` gains `boolean enabled` and `int tokenVersion` after `role`; keep `isAdmin()`.
2. `UserRepository` selects/maps `enabled` and `token_version`; `insert(...)` keeps DB defaults and returns enabled=true / version 0; signatures unchanged.
3. `JwtService.issue(userId, username, role, int tokenVersion)` adds `.claim("ver", tokenVersion)`; remove the 3-arg overload.
4. `AuthService.login` rejects disabled accounts with 401 "账号已停用，请联系管理员" and issues with `user.tokenVersion()`. `AuthService.register` assigns the built-in `student` role; missing role row is a 500, not a silently role-less user.
5. `AccessControlRepository.assignRoleByCode(userId, roleCode)` inserts via `INSERT ... SELECT ... WHERE code = :code AND enabled = TRUE`; zero rows is an error.
6. `AccessStatusWebFilter` validates the account before the chain, publishes `EffectiveAccess` under `EffectiveAccess.class.getName()`, rejects disabled/stale-version accounts, does not buffer SSE, and skips requests without a bearer token.
7. Tests: H2 test seed with built-in roles/permissions/grants (idempotent), integration test for register → me 200 → disable → 401 → bump version → 401 → fresh login 200; unit test for disabled login / registration role.

## Global constraints

- Java 25 / Spring Boot 4.1 WebFlux; no new dependencies.
- Fail closed; never leak passwords, hashes or tokens.
- Role change takes effect on the next request; the JWT role claim is display-only.
- Test H2 DB is shared across contexts: seeds must be idempotent.
- Existing tests must stay green.
