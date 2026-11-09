# Task 5.2 fix report — permission catalog + role-permission matrix

Review verdict: **Needs fixes** (2 Important, several minors). Status after this pass: **all addressed**.

## Important

### 1. Missing/null body silently wiped the role's permission set — fixed

- `AccessControlController.replaceRolePermissions` still accepts `@RequestBody(required = false)` (so a
  `400` is ours, not the framework's), but a `null` body is now passed through as `null` and rejected.
- `RolePermissionService.replacePermissions` treats `request == null` as a shape error:
  `400 "permissions 不能为空"`, before the role is loaded (so, per the validation-first rule, no audit row).
- Only an explicit `[]` clears the role.
- Tests: `aMissingBodyDoesNotClearTheSet` (body-less `PUT` → 400, set unchanged, no extra SUCCESS row) and
  `aNullPermissionsFieldDoesNotClearTheSet` (`{}` and `{"permissions": null}` → 400, set unchanged).

### 2. Anti-escalation seam was unusable and a future guard bean would have been ignored — fixed

- Seam simplified to the data-only contract the review asked for:

  ```java
  Mono<Optional<String>> check(String actorId, String roleId, Set<String> requestedKeys);
  ```

  Empty `Optional` = allowed, present = denial reason. No private `Denial` (or any package type) appears in
  the signature, so an implementation in any module can be written. `PermissionEscalationGuard.permitAll()`
  remains the default.
- Injection: `RolePermissionService` takes `ObjectProvider<PermissionEscalationGuard>` and falls back to
  `permitAll()` when no bean exists. A guard bean registered in 5.7 is therefore used automatically, with no
  change to the service. The old `@Autowired`-pinned 3-arg / 4-arg pair on `AccessAdminService` is gone; the
  permission logic moved to its own `RolePermissionService` (also the review's optional extraction).
- A guard denial is audited as `DENIED` (reason = the returned string, the real before set, the requested
  set) in its own `REQUIRES_NEW` transaction, then returned as `403` with that reason. Verified end-to-end at
  the service level; the controller maps `ResponseStatusException` unchanged, so the client sees
  `{"status":403,"message":"<reason>"}`.
- Guard *errors* are deliberately not treated as denials: they abort the change (rollback) and surface as a
  server error rather than an unaudited silent rejection. Documented on the interface.
- Removed the dead `Mono<Boolean>` / `Optional::ofNullable` plumbing.
- Tests: `RolePermissionServiceTransactionTest` — `aDenyingGuardAuditsTheDenialAndReturnsForbidden`
  (DENIED row + reason + 403 + set unchanged, injected via `StaticListableBeanFactory` so the real
  `ObjectProvider` path is exercised) and `aPermissiveGuardAppliesTheChange`.

## Minor — addressed

- **Rollback test**: `aFailedSuccessAuditInsertRollsTheSetReplacementBack` — a repository stub whose
  `record` fails; the delete + inserts do not survive (keys stay at the pre-replace set, no extra SUCCESS row).
- **DENIED before-state**: the current keys are read before the catalog check, so an unknown-key denial now
  records the role's real set. Asserted in `anUnknownKeyIsRejectedAndAudited`
  (`before_json == ["chat.use","datasource.read"]`). Unknown role keeps `[]` (there is no set to report).
- **Duplicate import** in `AccessAdminService` removed (that file also lost the now-unused permission
  imports/constants/field in the extraction).
- **Comment fixed**: "One INSERT … VALUES per key" (they were never `INSERT … SELECT`).
- **Test stability**: no "latest row by `created_at DESC, id DESC`"; audit assertions now compare
  *distinct column values* (`auditDistinct`) and *distinct before→after transitions* (`auditTransitions`),
  so same-millisecond rows cannot flake.
- **Catalog ordering**: asserts the `module` sequence is sorted and the `module\u0000key` sequence equals its
  own sorted copy (module-major, then key), instead of `module + "." + key`.
- **Over-long key**: the `400` message echoes at most 32 characters (plus `…`) — `anOverLongKeyIsRejectedWithABoundedMessage`
  asserts the message is < 80 chars. The 256-key cap is applied **after** de-duplication:
  `aDuplicateHeavyRequestIsCappedAfterDeduplication` sends 300 copies of one key and succeeds.
- **Extraction**: permission-matrix logic now lives in `RolePermissionService` (catalog, read, replace,
  audit, guard seam); `AccessAdminService` keeps roles only. `AccessControlController` injects both.

## Validation

- `mvnw -o test` with `DASHSCOPE_API_KEY` unset: **BUILD SUCCESS — Tests run: 294, Failures: 0, Errors: 0,
  Skipped: 0** (was 287; +7: 4 new web cases, 3 new service cases).
- Focused runs during the pass: `AccessControlPermissionTest` (13), `RolePermissionServiceTransactionTest` (3),
  `AccessControlRoleTest` (18), `AccessAdminServiceTransactionTest` (4) — all green.

## Concerns / follow-ups

- `GET /roles/{id}/permissions` remains unaudited (reads are not mutations), matching 5.1.
- A guard bean must be unique; if several are registered, `ObjectProvider.getIfAvailable()` throws at
  startup (fail-fast, which is acceptable for a security seam).
- `PermissionEscalationGuard` still has no implementation: 5.7 registers it and resolves the actor's
  `EffectiveAccess`.
