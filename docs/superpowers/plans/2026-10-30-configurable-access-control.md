# Configurable Access Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace hardcoded role branches with database-backed roles, permissions, and data-scope policies; enforce them at the SQL chokepoint; add the admin management UI and audit trail; fix the CTE shadowing bypass.

**Architecture:** A new `access-control` capability stores roles, permissions, user-role assignments, datasource grants, per-table data-scope policies, and audit rows in the application database. A resolver turns the authenticated user into an `EffectiveAccess`/`DataScope` value; a policy engine turns that value into table/row decisions; the existing pipeline and AgentScope tool path both use the same SQL chokepoint. The frontend adds an admin area under `/admin/access-control` and permission-aware navigation.

**Tech Stack:** Java 25, Spring WebFlux, R2DBC, MySQL 8.4 (H2 MySQL mode for tests), JSqlParser, Vue 3 + TypeScript + Element Plus.

**Spec:** `openspec/changes/configurable-access-control/` — `proposal.md`, `specs/access-control/spec.md`, `design.md`, `tasks.md`.

## Global Constraints

- No new runtime dependency. JSqlParser is already present for the SQL rewriter.
- No AgentScope sandbox, approval workflow, periodic access review, SSO, column masking, or multi-tenant work in this plan.
- Every authorization decision is server-side; hiding a UI entry is never the security boundary.
- Unknown role, table, column, principal attribute, or missing policy → deny/fail closed.
- Preserve the effective behavior of the three seeded roles after migration.
- The CTE name must never exempt a protected table from policy.
- Keep the existing `app_user.role`, `user_scope`, and `datasource_access` tables for compatibility; the new model is additive and migrated.
- Use the project’s existing package conventions under `com.nekocong.text2sql`.
- Run the backend with JDK 25. On this machine: `$env:JAVA_HOME = "C:\Users\14364\.jdks\corretto-25.0.4.1"` before `.\mvnw.cmd test`.
- Commit after each task with the message shown in the task. Do not push or deploy during implementation.

## Review Focus

These are the failure modes the spec implies but a happy-path test may miss. Each has a test in the task that owns the code:

1. **CTE shadowing** — a CTE named like a protected table must not return unfiltered rows. Owner: Task 4.
2. **DENY/ALL/FILTER merge** — `DENY` must win over another role’s `ALL`; `ALL` must win over `FILTER`; multiple `FILTER`s are ORed. Owner: Task 3.
3. **Last-admin lockout / self-escalation** — a non-admin cannot grant themselves `access.manage`; the last holder cannot be removed. Owner: Task 7.
4. **Stale JWT role** — changing a user’s roles must affect the next request without re-login. Owner: Task 5.
5. **Migration on an existing database** — running the migration twice must not duplicate roles/scopes, and a legacy unknown role must fail closed. Owner: Task 1.

---

## Task 1: Schema, seed, and migration

**Files:**
- Modify: `neko-text2sql-backend/src/main/resources/db/app-schema.sql`
- Modify: `neko-text2sql-backend/src/main/resources/db/app-data.sql`
- Create: `neko-text2sql-backend/src/main/resources/db/migration/2026-10-30-access-control.sql`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/persistence/AccessControlSeedTest.java`
- Modify: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/persistence/SeedDataScriptTest.java`

**Interfaces:**
- Produces tables: `access_role`, `access_permission`, `access_role_permission`, `access_user_role`, `access_role_datasource`, `access_data_scope`, `access_audit`.
- Produces seeded role codes: `admin`, `student`, `counselor`.
- Produces permission keys: `chat.use`, `datasource.read`, `datasource.write`, `model.manage`, `access.manage`, `audit.read`, `conversation.read_all`, `access.bypass_data_scope`.

- [ ] **Step 1: Write the failing schema/seed test**

Create `AccessControlSeedTest` with a test that loads `db/app-schema.sql` and `db/app-data.sql` into the H2 test database (copy the `runScript` helper from `SeedDataScriptTest`) and asserts:

```java
assertThat(count("SELECT COUNT(*) FROM access_role WHERE built_in = TRUE")).isEqualTo(3);
assertThat(roleCodes()).containsExactlyInAnyOrder("admin", "student", "counselor");
assertThat(permissionKeys()).contains(
        "chat.use", "datasource.read", "datasource.write", "model.manage",
        "access.manage", "audit.read", "conversation.read_all", "access.bypass_data_scope");
assertThat(count("SELECT COUNT(*) FROM access_user_role")).isEqualTo(5);
assertThat(count("SELECT COUNT(*) FROM access_data_scope WHERE policy = 'FILTER'")).isGreaterThan(0);
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd neko-text2sql-backend; $env:JAVA_HOME = "C:\Users\14364\.jdks\corretto-25.0.4.1"; .\mvnw.cmd -Dtest=AccessControlSeedTest test`
Expected: FAIL because `access_role` does not exist.

- [ ] **Step 3: Add the schema tables**

Add the seven tables to `app-schema.sql` with portable MySQL/H2 types. Use `BIGINT` epoch millis for timestamps and `VARCHAR` app-generated ids consistent with existing tables. Recommended columns:

```sql
access_role(id, code, name, description, parent_id, built_in, enabled, created_at, updated_at)
access_permission(id, permission_key, module, action, description, built_in)
access_role_permission(role_id, permission_id)
access_user_role(user_id, role_id)
access_role_datasource(role_id, datasource_id)
access_data_scope(id, role_id, datasource_id, table_name, policy, column_name, principal_attribute)
access_audit(id, actor_id, action, entity_type, entity_id, before_json, after_json, result, created_at)
```

Add indexes on `access_user_role(user_id)`, `access_role_permission(role_id)`, `access_role_datasource(role_id)`, `access_data_scope(role_id, datasource_id, table_name)`, and `access_audit(created_at)`.

- [ ] **Step 4: Seed the built-in model**

Seed the three roles, the permission catalog, role-permission mappings, `access_user_role` for the five seeded users, `access_role_datasource` for `student`/`counselor` → `campus`, and `access_data_scope` rows equivalent to today’s `TablePolicy`:

- `student`: `student.no = principal.studentNo`, `score.student_id = principal.studentId`, `attendance.student_id = principal.studentId`, `class.id IN principal.classIds`
- `counselor`: `student.class_id IN principal.classIds`, `score.class_id IN principal.classIds`, `attendance.class_id IN principal.classIds`, `class.id IN principal.classIds`
- `admin`: `access.bypass_data_scope` permission; no per-table rows required.

Use fixed ids and `INSERT IGNORE` so the seed is re-runnable. Keep the legacy `app_user.role`, `user_scope`, and `datasource_access` inserts for compatibility.

- [ ] **Step 5: Add the idempotent migration script**

Create `db/migration/2026-10-30-access-control.sql`. It must:
1. `CREATE TABLE IF NOT EXISTS` the seven tables and indexes.
2. Insert built-in roles/permissions with `INSERT IGNORE`.
3. Backfill `access_user_role` from `app_user.role`.
4. Backfill `access_role_datasource` from `datasource_access`.
5. Backfill `access_data_scope` from the built-in role behavior (same rows as Step 4).
6. Leave legacy tables untouched.

- [ ] **Step 6: Extend the seed test for idempotency and legacy behavior**

In `AccessControlSeedTest`, run the seed and migration twice and assert counts do not change. In `SeedDataScriptTest`, assert the legacy tables still contain the same data and the new `access_user_role` rows map the five seeded users.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `.\mvnw.cmd -Dtest=AccessControlSeedTest,SeedDataScriptTest test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add neko-text2sql-backend/src/main/resources/db neko-text2sql-backend/src/test/java/com/nekocong/text2sql/persistence
git commit -m "feat(access): add access-control schema, seed, and migration"
```

---

## Task 2: Access model and effective-access resolver

**Files:**
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/AccessPermissions.java`
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/PrincipalAttribute.java`
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/PrincipalAttributes.java`
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/PolicyType.java`
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/TableKey.java`
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/TablePolicyRule.java`
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/RoleSummary.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/DataScope.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/DataScopeResolver.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/repository/AccessRepository.java`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/security/DataScopeResolverTest.java`
- Test helper: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/security/DataScopeFixtures.java`

**Interfaces:**
- `DataScope` is redefined as:

```java
public record DataScope(
        String userId,
        String username,
        List<RoleSummary> roles,
        Set<String> permissions,
        Set<String> allowedDatasources,
        Map<TableKey, TablePolicyRule> tablePolicies,
        PrincipalAttributes principal) {
    public boolean hasPermission(String key);
    public boolean bypassesDataScope();
    public List<String> roleNames();
}
```

- `DataScopeResolver.resolve(String userId, String username) -> Mono<DataScope>`; no role string argument.
- `AccessRepository` additions: `findRolesForUser`, `findAllRoles`, `findPermissionsForRoles`, `findDataScopesForRoles`, `findDatasourcesForRoles`.
- `PrincipalAttribute` values: `USER_ID`, `USERNAME`, `STUDENT_ID`, `STUDENT_NO`, `CLASS_IDS`; `isSet()` is true only for `CLASS_IDS`.

- [ ] **Step 1: Write failing resolver tests**

Replace `DataScopeResolverTest` with tests that seed roles/permissions/scopes directly and assert:

```java
@Test void studentResolvesRolesPermissionsAndPrincipalAttributes()
@Test void counselorResolvesManagedClassesAndDatasourceGrants()
@Test void adminResolvesBypassPermissionWithoutScopeRows()
@Test void multiRoleUserGetsPermissionUnion()
@Test void childRoleInheritsParentPermissions()
@Test void disabledRoleIsIgnored()
@Test void unknownOrMissingRoleIsRejected()
@Test void missingStudentAttributeIsRejected()
```

Use `DataScopeFixtures.student()`, `.counselor()`, `.admin()`, and `.custom(...)` in later tests. The fixture should build a `DataScope` directly; do not call the resolver in unit tests that only need a scope.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd -Dtest=DataScopeResolverTest test`
Expected: FAIL because the resolver still takes a role string and `DataScope` still has `isStudent()`.

- [ ] **Step 3: Implement the value objects and repository queries**

Implement the records/enums. `TableKey` normalizes the datasource id and lowercases the table name. `TablePolicyRule` has static factories `all()`, `deny()`, `filter(column, attribute)` and validates that `ALL`/`DENY` carry no column/attribute while `FILTER` carries both.

Extend `AccessRepository` with queries that join the new tables. Keep the existing `scopes(userId)` query for principal attributes. Resolve role inheritance in Java: load all roles, then walk `parentId` with a visited set; reject cycles by throwing a 409/403-style configuration error.

- [ ] **Step 4: Implement the resolver**

`DataScopeResolver.resolve(userId, username)`:

1. Load roles for the user and all roles for parent resolution.
2. Walk each role’s parent chain; collect enabled roles; if a cycle is found, fail closed.
3. Union permissions and datasource grants.
4. Load data-scope rows; map `(datasourceId, tableName)` to `TablePolicyRule`.
5. Load `user_scope`; build `PrincipalAttributes`.
6. If no enabled roles → 403 “当前账号没有可用角色”.
7. Return `DataScope`.

Do not special-case `admin` by name. `admin` bypass comes from the `access.bypass_data_scope` permission.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\mvnw.cmd -Dtest=DataScopeResolverTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security neko-text2sql-backend/src/main/java/com/nekocong/text2sql/repository neko-text2sql-backend/src/test/java/com/nekocong/text2sql/security
git commit -m "feat(access): resolve effective roles, permissions, and data scope"
```

---

## Task 3: Data-scope policy engine

**Files:**
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/DataScopePolicyEngine.java`
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/sql/SqlIdentifiers.java`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/security/DataScopePolicyEngineTest.java`

**Interfaces:**
- `DataScopePolicyEngine`:
  - `boolean allows(DataScope scope, String datasourceId, String table)`
  - `void requireAllowed(DataScope scope, String datasourceId, String table)`
  - `Optional<String> filterFor(DataScope scope, String datasourceId, String table)`
  - `Set<String> visibleTables(DataScope scope, String datasourceId, Collection<String> candidates)`
- `SqlIdentifiers.requireColumn(String)`, `quoteIdentifier(String)`, `quoteLiteral(String)`.

- [ ] **Step 1: Write failing policy-engine tests**

```java
@Test void unconfiguredTableIsDenied()
@Test void denyWinsOverAllAcrossRoles()
@Test void allWinsOverFilterAcrossRoles()
@Test void filtersFromMultipleRolesAreOred()
@Test void missingPrincipalAttributeIsDenied()
@Test void bypassPermissionAllowsEveryTable()
@Test void filterUsesSingleValueEquality()
@Test void filterUsesClassIdInList()
@Test void invalidColumnIsRejectedAtRuntime()
```

For the multi-role cases, build a `DataScope` with two `RoleSummary` values and a map containing two rules for the same `TableKey`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd -Dtest=DataScopePolicyEngineTest test`
Expected: FAIL because the engine does not exist.

- [ ] **Step 3: Implement the engine and identifier helper**

Rules:
- If `scope.bypassesDataScope()` → allow all, no filter.
- Look up `new TableKey(datasourceId, table)`; absent → deny.
- Collect the rules for the user’s roles: `DENY` wins; otherwise `ALL` wins; otherwise OR the `FILTER` predicates.
- `FILTER` predicate:
  - single-value attribute → `column = 'value'`
  - `CLASS_IDS` → `column IN (1,2)`
- Missing/empty attribute value → deny (do not emit an empty `IN ()`).
- Quote identifiers with backticks after validating `[A-Za-z_][A-Za-z0-9_]*`; quote literals by doubling `'` and escaping `\`.
- `visibleTables` returns only tables with an effective grant (`ALL` or `FILTER`), not `DENY`/absent.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\mvnw.cmd -Dtest=DataScopePolicyEngineTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/DataScopePolicyEngine.java neko-text2sql-backend/src/main/java/com/nekocong/text2sql/sql/SqlIdentifiers.java neko-text2sql-backend/src/test/java/com/nekocong/text2sql/security/DataScopePolicyEngineTest.java
git commit -m "feat(access): add data-scope policy engine"
```

---

## Task 4: SQL rewriter, CTE fix, and enforcement integration

**Files:**
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/sql/DataScopeRewriter.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/schema/FullSchemaProvider.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/schema/RagSchemaProvider.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/schema/SchemaProvider.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/tool/SqlTools.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/service/Text2SqlPipelineService.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/service/Text2SqlAgentService.java` (type-only changes if needed)
- Delete: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/TablePolicy.java`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/sql/DataScopeRewriterTest.java`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/sql/DataPermissionIntegrationTest.java`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/service/Text2SqlPipelineServiceTest.java`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/tool/SqlToolsTest.java`

**Interfaces:**
- `DataScopeRewriter` constructor takes `DataScopePolicyEngine`; `rewrite(String sql, DataScope scope)`.
- `SchemaProvider.renderForPrompt(String datasourceId, DataScope scope, String question)` unchanged signature.
- `SqlTools` methods keep `DataScope` parameters; they are injected via AgentScope `RuntimeContext`.
- `Text2SqlPipelineService.scopedExplanation`/`callerContext` use role display names and principal attributes, not `isStudent()`/`isCounselor()`.

- [ ] **Step 1: Write failing CTE tests**

Add to `DataScopeRewriterTest`:

```java
@Test void rejectsCteNamedLikeProtectedTable()
@Test void filtersProtectedTableInsideCteBody()
@Test void rejectsRecursiveCte()
@Test void stillAllowsLegalCteReference()
@Test void rejectsCteCollisionWithSchemaQualifiedBody()
```

Use `DataScopeFixtures.student()` and assert `SqlPermissionException` for the collision/recursive cases, and assert the injected predicate for the legal body case.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd -Dtest=DataScopeRewriterTest test`
Expected: FAIL; the current rewriter allows the same-name CTE and does not reject recursive CTEs.

- [ ] **Step 3: Fix CTE scoping**

In `rewriteSelect`:

- Build the visible CTE set cumulatively; while rewriting a non-recursive CTE body, exclude the current CTE’s own name.
- Before rewriting a CTE, reject if its name equals a protected table name (compare against the policy engine’s known table set or the policy map keys for the datasource).
- Reject `With.isRecursive()` outright in this phase.
- Keep the existing fail-closed parse behavior.
- Continue rewriting subqueries/unions/joins with the same scope.

- [ ] **Step 4: Integrate the policy engine**

Replace all `TablePolicy` calls with `DataScopePolicyEngine`:

- `DataScopeRewriter` uses `allows`/`filterFor`.
- `FullSchemaProvider`/`RagSchemaProvider` use `visibleTables` and `allows` instead of `tablesFor(role)`.
- `SqlTools.listTables`/`describeTable` use the same engine.
- `Text2SqlPipelineService` explanation/caller context:
  - `scopedExplanation`: `"结果已按当前账号的数据范围（角色：" + String.join("、", scope.roleNames()) + "）过滤"`.
  - `callerContext`: use `scope.bypassesDataScope()` for the unrestricted wording; otherwise include role names, studentNo, classIds and a generic “我/我的 = 当前账号本人” instruction. Do not branch on `student`/`counselor` names.
- Remove `TablePolicy` after all usages are migrated.

- [ ] **Step 5: Update tests and run**

Update the existing `DataScopeRewriterTest` fixtures to `DataScopeFixtures`; update `DataPermissionIntegrationTest`, `Text2SqlPipelineServiceTest`, and `SqlToolsTest` for the new `DataScope` shape and policy engine. Run:

`.\mvnw.cmd -Dtest=DataScopeRewriterTest,DataPermissionIntegrationTest,Text2SqlPipelineServiceTest,SqlToolsTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add neko-text2sql-backend/src/main/java/com/nekocong/text2sql/sql neko-text2sql-backend/src/main/java/com/nekocong/text2sql/schema neko-text2sql-backend/src/main/java/com/nekocong/text2sql/tool neko-text2sql-backend/src/main/java/com/nekocong/text2sql/service neko-text2sql-backend/src/test/java/com/nekocong/text2sql/sql neko-text2sql-backend/src/test/java/com/nekocong/text2sql/service neko-text2sql-backend/src/test/java/com/nekocong/text2sql/tool
git commit -m "feat(access): enforce policy engine and fix CTE shadowing"
```

---

## Task 5: Permission-aware auth and controllers

**Files:**
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/AccessGuard.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/config/SecurityConfig.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/controller/ConversationController.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/controller/Text2SqlController.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/controller/DataSourceController.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/controller/ModelConfigController.java` if it uses role checks
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/service/AuthService.java`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/web/ConversationRoleAccessTest.java`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/web/Text2SqlControllerScopeTest.java`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/web/AuthControllerTest.java`

**Interfaces:**
- `AccessGuard.require(String userId, String username, String permission) -> Mono<DataScope>`; throws 403 when absent.
- `AccessPermissions` constants from Task 2.
- `SecurityConfig` keeps `authenticated()` for `/api/**` but removes `hasRole("ADMIN")` path checks; controllers enforce permissions.
- `AuthService.register` inserts an `access_user_role` row for the built-in `student` role after user creation.

- [ ] **Step 1: Write failing permission tests**

Add to `ConversationRoleAccessTest` / `Text2SqlControllerScopeTest`:

```java
@Test void customRoleWithoutChatUseGets403OnStream()
@Test void customRoleWithChatUseCanStream()
@Test void roleChangeAppliesToNextRequestWithoutReLogin()
@Test void conversationReadAllIsPermissionNotRoleName()
```

Add to `AuthControllerTest`:

```java
@Test void registrationAssignsSeedStudentRole()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd -Dtest=ConversationRoleAccessTest,Text2SqlControllerScopeTest,AuthControllerTest test`
Expected: FAIL because controllers still call `Roles.isAdmin` and registration does not write `access_user_role`.

- [ ] **Step 3: Implement `AccessGuard` and update controllers**

- Inject `DataScopeResolver` into `AccessGuard`.
- `require` resolves the scope and checks `scope.hasPermission(permission)`; return 403 with a readable message otherwise.
- Replace `Roles.isAdmin(jwt)`:
  - conversation list/rename/delete/messages → `conversation.read_all` for the “see all” flag.
  - text2sql stream/query → `chat.use`.
  - datasource GET → `datasource.read`; POST/PUT/DELETE → `datasource.write`.
  - admin controllers → `access.manage` (Task 6/7 will add the new endpoints).
- `SecurityConfig`: remove `hasRole("ADMIN")` blocks; keep `authenticated()` for API routes and public routes unchanged. The controller guard is the authorization layer.
- `AuthService.register`: after inserting the user, insert `access_user_role(user_id, role_id)` for the built-in `student` role; if the role is missing/disabled, fail registration with a clear error.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\mvnw.cmd -Dtest=ConversationRoleAccessTest,Text2SqlControllerScopeTest,AuthControllerTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add neko-text2sql-backend/src/main/java/com/nekocong/text2sql/security/AccessGuard.java neko-text2sql-backend/src/main/java/com/nekocong/text2sql/config/SecurityConfig.java neko-text2sql-backend/src/main/java/com/nekocong/text2sql/controller neko-text2sql-backend/src/main/java/com/nekocong/text2sql/service/AuthService.java neko-text2sql-backend/src/test/java/com/nekocong/text2sql/web
git commit -m "feat(access): enforce permissions on controllers and registration"
```

---

## Task 6: Admin API for roles and permissions

**Files:**
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/access/AccessControlService.java`
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/access/AccessControlRepository.java`
- Create: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/controller/AccessControlController.java`
- Create DTOs under `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/controller/dto/access/`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/web/AccessControlControllerTest.java`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/access/AccessControlServiceTest.java`

**Interfaces:**
- `AccessControlController` endpoints:
  - `GET /api/admin/access-control/roles`
  - `POST /api/admin/access-control/roles`
  - `PUT /api/admin/access-control/roles/{id}`
  - `DELETE /api/admin/access-control/roles/{id}`
  - `GET /api/admin/access-control/roles/{id}/permissions`
  - `PUT /api/admin/access-control/roles/{id}/permissions`
  - `GET /api/admin/access-control/permissions`
  - `GET /api/admin/access-control/me`
- `AccessControlService` methods:
  - `Mono<List<RoleView>> listRoles()`
  - `Mono<RoleView> createRole(CreateRoleRequest, String actorId)`
  - `Mono<RoleView> updateRole(String id, UpdateRoleRequest, String actorId)`
  - `Mono<Void> deleteRole(String id, String actorId)`
  - `Mono<List<PermissionView>> listPermissions()`
  - `Mono<RolePermissionsView> getRolePermissions(String roleId)`
  - `Mono<Void> setRolePermissions(String roleId, Set<String> keys, String actorId)`
  - `Mono<MeAccessView> me(String userId, String username)`

- [ ] **Step 1: Write failing controller/service tests**

```java
@Test void adminCanCreateRoleAndListIt()
@Test void duplicateRoleCodeReturns409()
@Test void cannotChangeRoleCode()
@Test void cannotDeleteBuiltInAdmin()
@Test void permissionsAreGroupedByModule()
@Test void settingRolePermissionsUpdatesEffectiveAccessOnNextRequest()
@Test void auditRowIsWrittenForRoleAndPermissionChanges()
@Test void nonAccessManagerGets403()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd -Dtest=AccessControlControllerTest,AccessControlServiceTest test`
Expected: FAIL because the endpoints/service do not exist.

- [ ] **Step 3: Implement the service, repository, controller, and DTOs**

- Validate role code `^[a-z][a-z0-9_-]*$`, unique, immutable after create.
- Reject parent changes that create a cycle; reject deleting/disabling a built-in admin.
- Reject assigning unknown permission keys.
- Write `access_audit` inside the same transaction as each mutation; include before/after JSON and actor.
- `me` returns the effective roles/permissions for the current user so the frontend can hide entries.
- Every endpoint calls `AccessGuard.require(..., access.manage)`, except `me` which only requires authentication.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\mvnw.cmd -Dtest=AccessControlControllerTest,AccessControlServiceTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add neko-text2sql-backend/src/main/java/com/nekocong/text2sql/access neko-text2sql-backend/src/main/java/com/nekocong/text2sql/controller/AccessControlController.java neko-text2sql-backend/src/main/java/com/nekocong/text2sql/controller/dto/access neko-text2sql-backend/src/test/java/com/nekocong/text2sql/web/AccessControlControllerTest.java neko-text2sql-backend/src/test/java/com/nekocong/text2sql/access
git commit -m "feat(access): add role and permission admin API"
```

---

## Task 7: Admin API for data scopes, users, datasources, audit, and anti-escalation

**Files:**
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/access/AccessControlService.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/access/AccessControlRepository.java`
- Modify: `neko-text2sql-backend/src/main/java/com/nekocong/text2sql/controller/AccessControlController.java`
- Modify DTOs under `controller/dto/access/`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/web/AccessControlControllerTest.java`
- Test: `neko-text2sql-backend/src/test/java/com/nekocong/text2sql/access/AccessControlServiceTest.java`

**Interfaces:**
- Endpoints:
  - `GET/PUT /api/admin/access-control/data-scopes`
  - `GET /api/admin/access-control/users?query=`
  - `PUT /api/admin/access-control/users/{userId}/roles`
  - `GET /api/admin/access-control/datasources`
  - `PUT/DELETE /api/admin/access-control/roles/{roleId}/datasources/{datasourceId}`
  - `GET /api/admin/access-control/audit`
- `AccessControlService` additions:
  - `Mono<List<DataScopeView>> listDataScopes(String roleId, String datasourceId)`
  - `Mono<Void> upsertDataScope(UpsertDataScopeRequest, String actorId)`
  - `Mono<List<UserSummaryView>> searchUsers(String query)`
  - `Mono<Void> setUserRoles(String userId, Set<String> roleIds, String actorId)`
  - `Mono<DatasourceMatrixView> datasourceMatrix()`
  - `Mono<Void> grantDatasource(String roleId, String datasourceId, String actorId)`
  - `Mono<Void> revokeDatasource(String roleId, String datasourceId, String actorId)`
  - `Mono<List<AuditView>> listAudit(int limit)`

- [ ] **Step 1: Write failing tests**

```java
@Test void dataScopeRejectsFreeTextSql()
@Test void dataScopeRejectsUnknownColumn()
@Test void dataScopeRejectsUnknownPrincipalAttribute()
@Test void dataScopeDenyAllFilterSemanticsArePersisted()
@Test void userRoleAssignmentAppliesOnNextRequest()
@Test void datasourceMatrixMigratesAndUpdates()
@Test void auditRequiresAuditRead()
@Test void userCannotGrantSelfAccessManage()
@Test void lastAccessManagerCannotBeRemoved()
@Test void builtInAdminRoleCannotBeDisabled()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd -Dtest=AccessControlControllerTest,AccessControlServiceTest test`
Expected: FAIL because these endpoints do not exist.

- [ ] **Step 3: Implement data-scope validation and CRUD**

- Validate table/column against the datasource metadata (`DataSourceRegistry`/schema introspection) and principal attribute against the enum.
- Reject `FILTER` with missing column/attribute; reject `ALL`/`DENY` carrying a column.
- Persist one row per role/datasource/table; upsert replaces the existing row.
- Write audit rows with before/after.
- Reject raw SQL strings anywhere in the request DTO.

- [ ] **Step 4: Implement users, datasources, audit, and anti-escalation**

- User search by username/display name; assignment replaces the user’s role set and audits.
- Datasource matrix returns roles × datasources with grants; grant/revoke writes `access_role_datasource`.
- Audit list is read-only and requires `audit.read`.
- Anti-escalation: a non-admin cannot grant a permission they do not hold; the last `access.manage` holder cannot be removed; the built-in admin role cannot be deleted/disabled; denied attempts are audited with result `DENIED`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\mvnw.cmd -Dtest=AccessControlControllerTest,AccessControlServiceTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add neko-text2sql-backend/src/main/java/com/nekocong/text2sql/access neko-text2sql-backend/src/main/java/com/nekocong/text2sql/controller/AccessControlController.java neko-text2sql-backend/src/main/java/com/nekocong/text2sql/controller/dto/access neko-text2sql-backend/src/test/java/com/nekocong/text2sql/web/AccessControlControllerTest.java neko-text2sql-backend/src/test/java/com/nekocong/text2sql/access
git commit -m "feat(access): add data scope, user role, datasource, and audit APIs"
```

---

## Task 8: Frontend access-control foundation

**Files:**
- Create: `neko-text2sql-frontend/src/api/accessControl.ts`
- Create: `neko-text2sql-frontend/src/stores/access.ts`
- Modify: `neko-text2sql-frontend/src/types.ts`
- Modify: `neko-text2sql-frontend/src/router/index.ts`
- Modify: `neko-text2sql-frontend/src/App.vue`
- Test: `npm run build`; manual route/permission checks.

**Interfaces:**
- `accessControl.ts` exports typed functions matching Task 6/7 endpoints, including `getMyAccess()`.
- `stores/access.ts` loads `/admin/access-control/me` once after login and exposes `has(permission)`.
- Router meta adds `permission?: string`; the guard calls the store and redirects to `/` when absent.
- Nav shows “权限管理” only when `access.manage` is present.

- [ ] **Step 1: Add types and API client**

Define `RoleView`, `PermissionView`, `RolePermissionsView`, `DataScopeView`, `UserSummaryView`, `DatasourceMatrixView`, `AuditView`, `MeAccessView`, and request types. Use the existing `http` client.

- [ ] **Step 2: Add the access store and route guard**

After authentication restore, call `getMyAccess()`; expose `permissions: Set<string>` and `has(key)`. On logout, clear it. If the call fails with 401, let the existing interceptor handle it.

- [ ] **Step 3: Wire router and navigation**

Add route:

```ts
{
  path: '/admin/access-control',
  name: 'access-control',
  component: () => import('@/views/AccessControlView.vue'),
  meta: { title: '权限管理', permission: 'access.manage' },
}
```

Add the nav item only when `access.has('access.manage')`. Keep `/admin/datasource-access` working or redirect it to the new page (choose redirect to avoid two admin pages).

- [ ] **Step 4: Build and verify**

Run: `cd neko-text2sql-frontend; npm run build`
Expected: exit 0.

- [ ] **Step 5: Commit**

```bash
git add neko-text2sql-frontend/src/api/accessControl.ts neko-text2sql-frontend/src/stores/access.ts neko-text2sql-frontend/src/types.ts neko-text2sql-frontend/src/router/index.ts neko-text2sql-frontend/src/App.vue
git commit -m "feat(ui): add access-control client, store, and route"
```

---

## Task 9: Frontend Roles and Permissions tabs

**Files:**
- Create: `neko-text2sql-frontend/src/views/AccessControlView.vue`
- Create: `neko-text2sql-frontend/src/components/access/RolesPanel.vue`
- Create: `neko-text2sql-frontend/src/components/access/PermissionsPanel.vue`
- Test: `npm run build`; manual create/edit/disable/cycle-error and permission matrix.

**Interfaces:**
- `AccessControlView` owns the tab state and the page header; each panel loads its own data through `accessControl.ts`.
- `RolesPanel` emits `changed` after a mutation so the parent can refresh effective access.
- `PermissionsPanel` accepts the selected role id and renders the grouped matrix.

- [ ] **Step 1: Build the page shell and Roles tab**

Tabs: 角色 / 权限 / 数据范围 / 用户 / 数据源 / 审计. Use the existing `ac-page`, `ac-page-header`, `ac-surface` primitives. Roles tab: table with code, name, parent, built-in, enabled; create/edit dialog; disable action with confirmation; show cycle/duplicate errors inline.

- [ ] **Step 2: Build the Permissions tab**

Role selector; permission list grouped by `module`; checkbox matrix; save button; success/error feedback. Disable editing for the built-in admin’s protected permissions only if required by the API.

- [ ] **Step 3: Build and manually verify**

Run: `npm run build`
Manual: create a role, assign `chat.use`, verify the role appears; toggle a permission and reload.

- [ ] **Step 4: Commit**

```bash
git add neko-text2sql-frontend/src/views/AccessControlView.vue neko-text2sql-frontend/src/components/access
git commit -m "feat(ui): add access-control roles and permissions tabs"
```

---

## Task 10: Frontend Data scope, Users, Datasource, and Audit tabs

**Files:**
- Create: `neko-text2sql-frontend/src/components/access/DataScopePanel.vue`
- Create: `neko-text2sql-frontend/src/components/access/UsersPanel.vue`
- Create: `neko-text2sql-frontend/src/components/access/DatasourcePanel.vue`
- Create: `neko-text2sql-frontend/src/components/access/AuditPanel.vue`
- Modify: `neko-text2sql-frontend/src/views/AccessControlView.vue`
- Test: `npm run build`; manual CRUD/permission checks.

**Interfaces:**
- `DataScopePanel`: role + datasource selectors, table list, `ALL`/`FILTER`/`DENY` selector, column/attribute selectors, effective preview, save.
- `UsersPanel`: search, role assignment, save.
- `DatasourcePanel`: role × datasource matrix with grant/revoke.
- `AuditPanel`: read-only table with expandable before/after.

- [ ] **Step 1: Implement Data scope tab**

Load datasource metadata for the selected datasource; only allow existing tables/columns; show the merge preview for the selected role; reject invalid combinations client-side and surface server 422 messages.

- [ ] **Step 2: Implement Users, Datasource, and Audit tabs**

Users: search box, user table, role multi-select, save. Datasource: reuse the existing matrix layout with role ids. Audit: paginated/limited table, actor, time, entity, result, before/after detail.

- [ ] **Step 3: Build and manually verify**

Run: `npm run build`
Manual: create a filtered `score` policy for a custom role; assign a user; verify the data scope preview; grant/revoke a datasource; check audit entries.

- [ ] **Step 4: Commit**

```bash
git add neko-text2sql-frontend/src/components/access neko-text2sql-frontend/src/views/AccessControlView.vue
git commit -m "feat(ui): add data scope, users, datasource, and audit tabs"
```

---

## Task 11: Documentation and integration verification

**Files:**
- Modify: `docs/nl2sql.md`
- Modify: `docs/deploy.md`
- Modify: `README.md`
- Test: full backend suite, frontend build, migration, E2E.

- [ ] **Step 1: Update docs**

Document:
- The built-in roles and permission catalog.
- The `DENY` > `ALL` > `FILTER` merge rule.
- The CTE-safe guarantee.
- The admin page and the “next request” effect.
- The migration script and the existing-volume warning.

- [ ] **Step 2: Run the backend suite**

```powershell
cd neko-text2sql-backend
$env:JAVA_HOME = "C:\Users\14364\.jdks\corretto-25.0.4.1"
.\mvnw.cmd test
```

Expected: all tests pass.

- [ ] **Step 3: Run the frontend build and tests**

```powershell
cd neko-text2sql-frontend
npm test
npm run build
```

Expected: both exit 0.

- [ ] **Step 4: Migration and parity check**

- Apply the seed + migration to a fresh H2/MySQL test database.
- Verify `admin`, `student01`, `counselor01` effective access matches the pre-change behavior.
- Run the CTE regression and confirm a same-name CTE cannot read a protected table.
- Create a custom role with a filtered `score` policy, assign a user, and verify both the pipeline and AgentScope path return the filtered result; revoke and verify the next request is denied.

- [ ] **Step 5: Final commit**

```bash
git add docs README.md
git commit -m "docs(access): document configurable access control"
```

- [ ] **Step 6: Workflow follow-up**

Before archiving the OpenSpec change:
- Archive `campus-data-permission` and reconcile its three-role requirement with the configurable model.
- Confirm no sandbox/approval/periodic-review work was included.
- Update `openspec/changes/configurable-access-control/tasks.md` checkboxes and run `openspec validate --strict`.

---

## Plan Self-Review

- **Spec coverage:** roles, permissions, multiple roles, inheritance, data-scope policies, merge semantics, SQL enforcement, CTE safety, admin UI, anti-escalation, audit, migration — each maps to a task above.
- **Deferred by design:** approval workflow, periodic review, SSO, column masking, sandbox. They are explicitly out of scope in the spec and proposal.
- **Type consistency:** `DataScope`, `TableKey`, `TablePolicyRule`, `PrincipalAttribute`, `AccessPermissions`, `AccessGuard`, and the API DTO names are introduced once and reused consistently.
- **Review focus tests:** CTE (Task 4), merge (Task 3), anti-escalation (Task 7), stale JWT (Task 5), migration (Task 1).
- **Known prerequisite:** the unarchived `campus-data-permission` change must be reconciled before this change is archived; implementation can proceed against the current code.
