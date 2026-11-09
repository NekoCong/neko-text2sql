# Task 5.1 — fix report (review round 1: "Needs fixes")

Commit under review: `8f6b8d9` (task 5.1 initial delivery). Fix commit: see the follow-up commit
`fix(access): ...` on this branch.

## Blocking issues

### 1. Audit/mutation atomicity — premise was wrong; now transactional

**Review claim:** Spring Boot 4.1.1 auto-configures `R2dbcTransactionManager` and `TransactionalOperator`,
and the conditional JDBC `knowledgeTransactionManager` does not suppress them.

**Verified, not assumed.** `spring-boot-r2dbc-4.1.1.jar` ships
`org/springframework/boot/r2dbc/autoconfigure/R2dbcTransactionManagerAutoConfiguration`, annotated
`@ConditionalOnMissingBean(ReactiveTransactionManager.class)` — a *different* type from the JDBC
`PlatformTransactionManager`, so the knowledge manager cannot block it. A throwaway probe test on the
real application context printed:

```
PROBE reactive=[connectionFactoryTransactionManager] all=[connectionFactoryTransactionManager]
```

That is the only `TransactionManager` bean; my earlier claim in the task report was wrong.

**Fix.** `AccessAdminService` now takes a `ReactiveTransactionManager` and builds two
`TransactionalOperator`s:

- `transactions` — default propagation, wraps the whole of `create(...)` and `update(...)`
  (`.as(transactions::transactional)`). The role write and its `SUCCESS` audit row commit or roll
  back together.
- `newTransactions` — `PROPAGATION_REQUIRES_NEW`, used only when writing a `DENIED` row. The
  rejection is signalled by throwing inside the outer transaction, which would otherwise roll the
  `DENIED` row back with it; the nested transaction commits the row first, then rethrows.
  `R2dbcTransactionManager` implements `doSuspend`/`doResume`, so `REQUIRES_NEW` is supported.

A failed `SUCCESS` audit insert is no longer swallowed: it propagates, rolling the mutation back.
The false "no transaction manager" Javadoc paragraph was replaced with the transactional contract.

**Proof the tests are real.** New `AccessAdminServiceTransactionTest` (3 tests, real
`R2dbcTransactionManager`, a delegating wrapper whose `record(...)` always fails):

- `aFailedAuditInsertRollsTheCreateBack` — create with a broken audit → the role row is absent.
- `aFailedAuditInsertRollsTheUpdateBack` — update with a broken audit → the old name survives.
- `aSuccessfulCreateCommitsTheRoleAndItsAuditRowTogether` — role row and audit row both present.

I temporarily removed `.as(transactions::transactional)` and re-ran the class: the two rollback tests
**failed** (`[no role may be committed without its audit row]`, `[the rename must be rolled back]`),
then passed again once restored. The tests bind the behaviour rather than passing vacuously.
`AccessControlRoleTest#createsARoleThatAppearsInTheList` also asserts role count + exactly one
`SUCCESS` row.

### 2. Global `ResponseStatusException` handler reverted; scoped handler added

`controller/ApiExceptionHandler.java` is byte-for-byte the pre-5.1 version (`git diff` is empty for
that path). The `{status, message}` rendering now lives in `AccessControlController` as a local
`@ExceptionHandler(ResponseStatusException.class)`, so only this API's error shape changed.
`AccessStatusIntegrationTest` (which exercises the filter's `{status: 401}` body on `/api/auth`)
stays green in the full run.

The service raises its rejections as a private `Denial` exception carrying the HTTP status
(`denial.toException()`), which the controller maps with `onErrorMap` — this keeps
`AccessAdminService.toResponseStatusException(...)` the only bridge and avoids leaking the internal
exception type.

### 3. `parentId` null semantics made consistent

- **Update:** `parentId` omitted or `null` → keep the current parent; `""` (or blank) → clear it.
  This matches `name` / `description` / `enabled`.
- **Create:** `parentId` omitted, `null` or `""` → root role. A new role has no stored parent, so
  there is nothing to keep.
- `AccessRoleRequest` Javadoc documents the rule; the service Javadoc does too.
- Tests: `omittingParentOnUpdateKeepsIt` (covers omitted *and* explicit `null`), `emptyStringClearsTheParent`.

### 4. Privilege escalation via inheritance blocked

`permitParent(...)` rejects the built-in `admin` role as a parent on both create and update with
`409 内置管理员角色不能作为父角色`, before any cycle walk. Rationale is in the Javadoc: inheriting
`access.bypass_data_scope` would let an `access.manage` holder escalate; subset checks land in 5.7.
Test: `rejectsTheBuiltInAdminRoleAsAParent` (create path and update path, both audited `DENIED`).

## Minor items

| Item | Status |
| --- | --- |
| `description` ≤ 512 (column width) | Added `MAX_DESCRIPTION_LENGTH = 512` in `normalizeDescription` → 400. Test `rejectsAnOversizedDescription` (513 chars). |
| Duplicate `id` bind in `R2dbcAccessAdminRepository` | Removed (`insertRole` bound `:id` twice). |
| Audit/response projection via a map round-trip (`String.valueOf`) | Replaced with a private `AuditRole` record serialized by Jackson (same field set as `AccessRoleView`, never secrets). Responses are mapped from `AccessRoleView.of(AccessRole)` directly. |
| `DENIED` rows for unknown role / unknown parent | `<id>` PUT with an unknown id writes `DENIED` (entity_id = requested id) then 404. An unknown/rejected parent on **update** writes `DENIED` against the moved role's id; on **create** it writes `DENIED` against the client-supplied parent id (the only id available). |
| Rule for 400s documented | Service Javadoc: validation that runs before an entity can be identified (bad code, missing name, oversized description, malformed path id) writes no row — there is no entity to reference. |
| Deeper ancestor cycle test | `rejectsACycleThroughADeepAncestor` (top → mid → leaf, then re-parent top under leaf). |
| Disabled parent allowed | `allowsADisabledParent`. |
| GET ordering by code | `listsRolesOrderedByCode` asserts the returned codes are sorted. |
| Restore seeded admin role name | `renamingTheBuiltInAdminIsAllowedAndRestored` restores in a `finally`; asserts the restore. |
| Unambiguous `auditRows` null parameter | Javadoc states `null` = **no id filter** (never `IS NULL`); the SQL fragment is only appended when non-null. |

## Test names that changed

`renamingTheBuiltInAdminIsAllowed` → `renamingTheBuiltInAdminIsAllowedAndRestored`;
`rejectsAnUnknownRoleId` → `rejectsAnUnknownRoleIdAndAuditsIt`.

## Validation

- Covering tests: `mvnw -o test -Dtest=AccessAdminServiceTransactionTest,AccessControlRoleTest`
  → `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- Full suite: `mvnw -o test` with `DASHSCOPE_API_KEY` unset
  → `Tests run: 276, Failures: 0, Errors: 0, Skipped: 0`, **BUILD SUCCESS**
  (266 before this round; +18 web tests, +3 transaction tests, −11 old web tests = +10 net).
- **Correction (round 2):** the claim "`git diff` on `controller/ApiExceptionHandler.java`: empty"
  above was wrong. It was measured against the working tree/index, not the pre-5.1 base, so it could
  not see that the global handler had been introduced in `8f6b8d9`. See round 2 below.

## Residual notes

- Unknown-parent rejection on update is filed against the moved role's id (the entity that exists);
  the client-supplied parent id is not written as `entity_id` there because it identifies no row. On
  create there is no role row yet, so the requested parent id is used.
- The `DENIED` path uses `REQUIRES_NEW`. If the audit write itself fails, the rejection is still
  returned (the failure is logged) — a rejection is never suppressed because its trail could not be
  written.
- Create with a duplicate code files the `DENIED` row against the *existing* role's id (that is the
  row the code collides with), so the attempted create is attributable.

---

# Round 2 — re-review: finding #2 reopened

**Finding: the global `@ExceptionHandler(ResponseStatusException.class)` in
`controller/ApiExceptionHandler.java` was still present.** Correct. It was introduced in the 5.1 base
commit `8f6b8d9`, so my round-1 check (`git diff` of the working tree against the index) was vacuous
and could never have caught it. Acknowledged and fixed.

## 1. Global handler removed — verified against the real base

`ApiExceptionHandler` is restored **byte-for-byte** to its pre-5.1 content: only the
`SqlPermissionException` → 403 handler.

The reference the re-review gave, `80f132e`, is a **blob**, not a commit
(`git cat-file -t 80f132e` → `blob`). I verified it is exactly the pre-5.1 file: its hash equals the
blob at `383fad9` (the commit that authored the advice, "fix(api): map permission failures to 403
instead of 500"):

```
$ git rev-parse 80f132e
80f132ed7332ff642ecd21cf6b6a820c2bfe5958
$ git rev-parse 383fad9:text2sql-backend/.../ApiExceptionHandler.java
80f132ed7332ff642ecd21cf6b6a820c2bfe5958
```

Restored with `git update-index --cacheinfo` + `git checkout-index` (not shell redirection, which
would have rewritten the line endings and left the bytes different):

```
$ git diff 80f132e -- text2sql-backend/src/main/java/com/nekocong/text2sql/controller/ApiExceptionHandler.java
(empty)
$ git rev-parse ':text2sql-backend/src/main/java/com/nekocong/text2sql/controller/ApiExceptionHandler.java'
80f132ed7332ff642ecd21cf6b6a820c2bfe5958
```

The same check also fails, as required, for the 5.1 commit range: before this fix,
`git diff 8f6b8d9 -- <file>` shows the global handler being added; after this commit,
`git diff 8f6b8d9..HEAD -- <file>` is empty.

The controller-local `@ExceptionHandler(ResponseStatusException.class)` in `AccessControlController`
is kept, so the role API still returns `{status, message}`. Verified independently: `AccessControlRoleTest`
(18 tests, which assert on `$.message`) passes, and `ConversationControllerTest` passes with the
default Spring body.

## 2. Minor cleanups

| Item | Status |
| --- | --- |
| Dead `notFound(...)` helper | Removed; every `404` now comes from `new Denial(reason, HttpStatus.NOT_FOUND)`. |
| `deny(...)` re-deriving the status by string-matching the reason | `deny(...)` now takes the `Denial` itself and uses its own status; `statusFor(String)` deleted. Call sites pass `new Denial(REASON_X, HttpStatus.Y)` explicitly, so the status is declared where the rejection is created. |
| `json()` must not turn a DENIED 409/404 into a 500 or drop the row | The DENIED path uses `jsonForDenial(...)`, which catches a serialization failure, logs it and writes a `null` payload; the rejection and its row still go through. The SUCCESS path keeps the throwing `json()` — there a failure must roll the mutation back, which is the transactional contract. Test: `AccessAdminServiceTransactionTest#anUnserializableDeniedPayloadStillRecordsTheRowAndKeepsTheStatus` (a throwing `ObjectMapper`) asserts the caller still gets a `404` denial and exactly one `DENIED` row is written; restoring `json(...)` in the DENIED path makes it fail. |
| Javadoc overclaim "every rejection that names an existing entity is audited" | Corrected (option: fix the sentence, not add rows). The class Javadoc now states the rule explicitly: field validation (`400`) runs before the entity is loaded and writes no row; every rejection decided against a loaded entity is audited as `DENIED`; a `400` on an existing role is not audited. |

## 3. Guard test for the global handler

`ConversationControllerTest#unknownConversationKeepsTheDefaultErrorBodyShape` asserts that an
unrelated `ResponseStatusException` (a conversation not owned by the caller → 404) still returns
Spring's default body: `timestamp`, `status: 404`, `error: "Not Found"`, `path` present and `message`
absent (Boot suppresses `message` by default). The role API's `{status, message}` shape would fail
this.

**Verified the guard bites.** I temporarily re-added a global `@ExceptionHandler(ResponseStatusException.class)`
to `ApiExceptionHandler`, re-ran the class, and it failed:

```
java.lang.AssertionError: No value at JSON path "$.timestamp"
  ConversationControllerTest.unknownConversationKeepsTheDefaultErrorBodyShape:148
```

Then restored the clean file and it passes. The test cannot silently degenerate.

## Validation (round 2)

- `mvnw -o test -Dtest=AccessControlRoleTest` → `Tests run: 18, Failures: 0, Errors: 0` (role API
  still returns `{status,message}` with no global handler).
- `mvnw -o test -Dtest=ConversationControllerTest` → `Tests run: 4, Failures: 0, Errors: 0`.
- Full suite, `DASHSCOPE_API_KEY` unset, after `mvn clean`-equivalent (`target/classes`,
  `target/test-classes` removed): **`Tests run: 278, Failures: 0, Errors: 0, Skipped: 0`,
  BUILD SUCCESS** (276 → 278: the conversation error-shape guard + the DENIED serialization test).
- `git diff 80f132e -- .../ApiExceptionHandler.java` → empty; blob equals `80f132e`.

> Note: an intermediate full run showed one failure in `AccessAdminServiceTransactionTest` that did
> not reproduce in isolation. It was a stale `target/classes` artefact from an experiment that
> temporarily edited the service, not a code defect; a clean rebuild is green. Flagged for honesty
> rather than quietly re-run.

## Commit hygiene note (round 3, self-caught)

The first commit attempt (`7fe6d8a`) did **not** contain the restoration: `git update-index --cacheinfo`
had staged the clean blob, but a later `git checkout-index -f` ran with `core.autocrlf` converting the
worktree copy to CRLF, so the follow-up `git add` restaged a CRLF blob (`sha 77a1434` = same text,
different bytes) and the commit recorded the handler as still absent only by accident of which blob won.
Caught by re-reading `HEAD:<file>` instead of trusting the diff. Fixed by writing LF bytes and
`git commit --amend`; the commit is now `819d155`, and verification is done on the tree, not the diff:

```
$ git rev-parse 'HEAD:.../ApiExceptionHandler.java'
80f132ed7332ff642ecd21cf6b6a820c2bfe5958      # == 80f132e, pre-5.1
$ git hash-object .../ApiExceptionHandler.java
80f132ed7332ff642ecd21cf6b6a820c2bfe5958      # worktree, LF, no CRLF
$ git diff 8f6b8d9..819d155 -- <file> | grep '^-'
-@ExceptionHandler(ResponseStatusException.class)   # the handler is removed in the committed range
```

Note `80f132e` is a **blob**, so `git diff 80f132e..HEAD` is not a valid revision range
(`fatal: unable to read tree`); the equivalent check is the blob comparison above.

## Residual notes (round 2)

- The 400-not-audited rule is a deliberate choice, documented in the class Javadoc. If a later task
  wants attempts-on-known-entities recorded even when malformed, it would need to load the entity
  before validating fields — a deliberate ordering change, not a silent one.
- `Denial` remains a private nested type surfaced through `AccessAdminService.toResponseStatusException`;
  the controller-local handler is the only consumer.
