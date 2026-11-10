# Agent Scope-Aware Answers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Agent-mode answers disclose the caller's data scope and stop interpreting permission-filtered results as missing data.

**Architecture:** Reuse one scope-describer for both the deterministic pipeline and the agent, inject it into the agent system prompt per request, add `dataScope` to the `query_database` tool envelope, and append a deterministic clarification when a restricted answer still claims absence.

**Tech Stack:** Java 25, Spring Boot 4.1 (WebFlux), AgentScope 2.0.4, JUnit 5, AssertJ, Mockito, Maven.

**Spec:** `openspec/changes/agent-scope-aware-answers/` (`proposal.md`, `specs/data-permission/spec.md`, `design.md`, `tasks.md`)

## Global Constraints

- Java 25 / Spring Boot 4.1.1; do not add dependencies.
- Do not change the permission enforcement (table whitelist, row rewrite, datasource guard), the HTTP API, or the SSE event vocabulary.
- Scope text must contain only the caller's own identifiers — never another class, student number, or out-of-scope table.
- Prompts and user-facing text are Simplified Chinese; Javadoc stays English like the rest of the backend.
- Agent scope comes only from the server-resolved `DataScope`; never from model output or user input.
- Run tests with Maven. On PowerShell, judge success by the `BUILD SUCCESS` line, not `$LASTEXITCODE`.
- Commit after each task with a conventional-commit message.

## Review Focus

1. Student asks about another class/student → the answer must state the permission limit, never "不存在".
2. A query returns 0 rows because of row filtering → "当前数据范围内没有可见记录", not "数据库中无数据".
3. Admin asks the same question → no scope disclaimer is added.
4. The answer already explains the scope → no duplicate clarification.
5. Scope text/tool metadata must not leak out-of-scope classes, students, or tables.

---

## File Structure

**Create**

- `text2sql-backend/src/main/java/com/nekocong/text2sql/security/DataScopeDescriber.java` — canonical scope text (`summary`, `promptBlock`, `restricted`).
- `text2sql-backend/src/main/java/com/nekocong/text2sql/security/DataScopeView.java` — serializable scope metadata for tool results.
- `text2sql-backend/src/main/java/com/nekocong/text2sql/security/ScopeAnswerGuard.java` — deterministic absence-claim clarification.
- `text2sql-backend/src/main/java/com/nekocong/text2sql/tool/ScopedQueryResult.java` — `{ result, dataScope }` tool envelope.
- Tests: `security/DataScopeDescriberTest.java`, `security/ScopeAnswerGuardTest.java`, `config/HarnessAgentFactoryPromptTest.java`, `web/AgentScopeAnswerStreamTest.java`.

**Modify**

- `service/Text2SqlPipelineService.java` — replace private `callerContext` with `DataScopeDescriber`.
- `config/HarnessAgentFactory.java` — `create(Model, DataScope)` + `systemPrompt(DataScope)`.
- `service/Text2SqlAgentService.java` — pass the scope into the factory.
- `tool/SqlTools.java` — serialize `ScopedQueryResult`.
- `controller/Text2SqlController.java` — accumulate deltas and append the clarification before `done`.
- `resources/prompts/agent-system.md` — `{{data_scope}}` block and answer rules.
- Tests: `service/Text2SqlPipelineServiceTest`, `tool/SqlToolsTest`, `prompt/PromptCatalogTest`, `config/HarnessAgentFactorySubagentsTest`.
- Docs: `docs/nl2sql.md`.

---

### Task 1: Canonical scope describer

**Files:**
- Create: `text2sql-backend/src/main/java/com/nekocong/text2sql/security/DataScopeDescriber.java`
- Test: `text2sql-backend/src/test/java/com/nekocong/text2sql/security/DataScopeDescriberTest.java`

**Interfaces:**
- Produces: `public static boolean restricted(DataScope)`, `public static String summary(DataScope)`, `public static String promptBlock(DataScope)`.
- `promptBlock` must return byte-for-byte the text the pipeline used before this change (see Task 2).

- [ ] **Step 1: Write the failing test**

```java
package com.nekocong.text2sql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class DataScopeDescriberTest {

    private static final DataScope STUDENT =
            new DataScope("u-1", "student", Set.of(1L), 1L, "2023001", Set.of("campus"));
    private static final DataScope COUNSELOR =
            new DataScope("u-2", "counselor", Set.of(1L, 2L), null, null, Set.of("campus"));
    private static final DataScope ADMIN = DataScope.admin("admin-1");

    @Test
    void summarizesEachRoleWithoutLeakingOtherObjects() {
        assertThat(DataScopeDescriber.summary(STUDENT))
                .isEqualTo("学生（仅本人：学号 2023001，班级 1）")
                .doesNotContain("2023002");
        assertThat(DataScopeDescriber.summary(COUNSELOR))
                .isEqualTo("辅导员（管理班级 1, 2）")
                .doesNotContain("3");
        assertThat(DataScopeDescriber.summary(ADMIN)).contains("全量").contains("不受");
    }

    @Test
    void keepsThePipelineWording() {
        assertThat(DataScopeDescriber.promptBlock(STUDENT))
                .contains("本人学号 2023001")
                .contains("student_no = '2023001'");
        assertThat(DataScopeDescriber.promptBlock(COUNSELOR))
                .contains("管理班级 id [1, 2]")
                .contains("class_id IN (...)");
        assertThat(DataScopeDescriber.promptBlock(ADMIN))
                .isEqualTo("管理员：可见全部数据，不需要按调用者过滤。");
    }

    @Test
    void onlyAdminsAreUnrestricted() {
        assertThat(DataScopeDescriber.restricted(ADMIN)).isFalse();
        assertThat(DataScopeDescriber.restricted(STUDENT)).isTrue();
        assertThat(DataScopeDescriber.restricted(null)).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=DataScopeDescriberTest test`
Expected: FAIL — `DataScopeDescriber` does not exist.

- [ ] **Step 3: Implement `DataScopeDescriber`**

```java
public final class DataScopeDescriber {

    private DataScopeDescriber() {}

    public static boolean restricted(DataScope scope) {
        return scope != null && !scope.isAdmin();
    }

    public static String summary(DataScope scope) {
        if (scope == null) {
            return "未识别角色：没有数据权限";
        }
        if (scope.isAdmin()) {
            return "管理员（全量数据，不受范围限制）";
        }
        if (scope.isStudent()) {
            return "学生（仅本人：学号 " + scope.studentNo() + "，班级 " + classes(scope) + "）";
        }
        if (scope.isCounselor()) {
            return "辅导员（管理班级 " + classes(scope) + "）";
        }
        return "未识别角色：没有数据权限";
    }

    public static String promptBlock(DataScope scope) {
        if (scope == null) {
            return "未识别角色：没有数据权限。";
        }
        if (scope.isAdmin()) {
            return "管理员：可见全部数据，不需要按调用者过滤。";
        }
        if (scope.isStudent()) {
            return "学生：本人学号 "
                    + scope.studentNo()
                    + "，所属班级 id "
                    + scope.classIds().stream().sorted().toList()
                    + "。问题中的“我/我的”指该学生本人，用 student_no = '"
                    + scope.studentNo()
                    + "' 过滤。";
        }
        if (scope.isCounselor()) {
            return "辅导员：管理班级 id "
                    + scope.classIds().stream().sorted().toList()
                    + "。问题中的“我管理的班/本班”指这些班级，用 class_id IN (...) 过滤。";
        }
        return "未识别角色：没有数据权限。";
    }

    private static String classes(DataScope scope) {
        return scope.classIds().isEmpty()
                ? "无"
                : scope.classIds().stream()
                        .sorted()
                        .map(String::valueOf)
                        .collect(Collectors.joining(", "));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=DataScopeDescriberTest test`
Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add text2sql-backend/src/main/java/com/nekocong/text2sql/security/DataScopeDescriber.java text2sql-backend/src/test/java/com/nekocong/text2sql/security/DataScopeDescriberTest.java
git commit -m "feat(security): add a canonical data-scope describer"
```

---

### Task 2: Pipeline reuses the canonical wording

**Files:**
- Modify: `text2sql-backend/src/main/java/com/nekocong/text2sql/service/Text2SqlPipelineService.java`
- Test: `text2sql-backend/src/test/java/com/nekocong/text2sql/service/Text2SqlPipelineServiceTest.java`

**Interfaces:**
- Consumes: `DataScopeDescriber.promptBlock(DataScope)` from Task 1.
- Produces: unchanged pipeline behavior; `callerContext` disappears.

- [ ] **Step 1: Add the failing assertion**

In `Text2SqlPipelineServiceTest`, add a field `private String lastCallerContext;`, assign it inside the stubbed `generate(...)` with `Text2SqlPipelineServiceTest.this.lastCallerContext = callerContext;`, and add:

```java
@Test
void studentScopeReachesTheGenerationPrompt() {
    pipelineReturning("SELECT 1", "ok").run(studentScope(), null, "我的成绩").block();

    assertThat(lastCallerContext)
            .contains("本人学号 2023001")
            .contains("student_no = '2023001'");
}
```

- [ ] **Step 2: Run it to confirm the baseline**

Run: `mvn -q -Dtest=Text2SqlPipelineServiceTest#studentScopeReachesTheGenerationPrompt test`
Expected: PASS — this is a characterization test. It pins the wording before the refactor and must stay green afterwards.

- [ ] **Step 3: Replace the private helper**

Delete `private static String callerContext(DataScope scope)` and change both call sites to `DataScopeDescriber.promptBlock(scope)` (import `com.nekocong.text2sql.security.DataScopeDescriber`). Keep `scopedExplanation` unchanged.

- [ ] **Step 4: Run the pipeline tests**

Run: `mvn -q -Dtest=Text2SqlPipelineServiceTest test`
Expected: BUILD SUCCESS, all pipeline tests pass (the wording is unchanged).

- [ ] **Step 5: Commit**

```bash
git add text2sql-backend/src/main/java/com/nekocong/text2sql/service/Text2SqlPipelineService.java text2sql-backend/src/test/java/com/nekocong/text2sql/service/Text2SqlPipelineServiceTest.java
git commit -m "refactor(security): share the scope wording with the pipeline"
```

---

### Task 3: Agent prompt carries the scope and answer rules

**Files:**
- Modify: `text2sql-backend/src/main/resources/prompts/agent-system.md`
- Test: `text2sql-backend/src/test/java/com/nekocong/text2sql/prompt/PromptCatalogTest.java`

**Interfaces:**
- Produces: template placeholder `{{data_scope}}`; the factory in Task 4 fills it.

- [ ] **Step 1: Write the failing test**

```java
@Test
void agentPromptCarriesDataScopeAndAnswerRules() {
    String prompt = catalog.render(
            "prompts/agent-system.md", Map.of("data_scope", "学生（仅本人：学号 2023001）"));

    assertThat(prompt)
            .contains("学生（仅本人：学号 2023001）")
            .contains("不得")
            .contains("不存在")
            .contains("探测")
            .contains("管理员")
            .doesNotContain("{{data_scope}}");
}
```

Add `import java.util.Map;` if missing.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=PromptCatalogTest#agentPromptCarriesDataScopeAndAnswerRules test`
Expected: FAIL — the placeholder is not in the template.

- [ ] **Step 3: Extend `agent-system.md`**

Append after the behaviour section:

```markdown
## 数据范围

{{data_scope}}

- 非管理员的结果只覆盖上述范围。结果为空，或问题涉及范围外的班级/学生时，必须说明这是当前账号的数据范围限制，
  **不得**说"数据库中没有""不存在"或等价表述。
- 不得为了判断范围外数据是否存在而额外调用工具（如计数、存在性检查）。
- 管理员的数据范围不受限，不要添加数据范围免责声明。
- `query_database` 返回 `{ "result": …, "dataScope": { … } }`；`dataScope` 是本次结果的范围依据。
```

- [ ] **Step 4: Run the prompt tests**

Run: `mvn -q -Dtest=PromptCatalogTest test`
Expected: BUILD SUCCESS, including the existing agent-prompt test.

- [ ] **Step 5: Commit**

```bash
git add text2sql-backend/src/main/resources/prompts/agent-system.md text2sql-backend/src/test/java/com/nekocong/text2sql/prompt/PromptCatalogTest.java
git commit -m "feat(prompt): teach the agent its data scope and answer rules"
```

---

### Task 4: Build the agent with the caller's scope

**Files:**
- Modify: `text2sql-backend/src/main/java/com/nekocong/text2sql/config/HarnessAgentFactory.java`
- Modify: `text2sql-backend/src/main/java/com/nekocong/text2sql/service/Text2SqlAgentService.java`
- Test: `text2sql-backend/src/test/java/com/nekocong/text2sql/config/HarnessAgentFactoryPromptTest.java`

**Interfaces:**
- Consumes: `DataScopeDescriber.promptBlock`.
- Produces: `public HarnessAgent create(Model model, DataScope scope)`; package-private `String systemPrompt(DataScope scope)` for tests.

- [ ] **Step 1: Write the failing test**

```java
package com.nekocong.text2sql.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.prompt.PromptCatalog;
import com.nekocong.text2sql.security.DataScope;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HarnessAgentFactoryPromptTest {

    private final HarnessAgentFactory factory =
            new HarnessAgentFactory(new AgentProperties(), null, new PromptCatalog());

    @Test
    void studentPromptCarriesTheOwnScopeAndRules() {
        String prompt = factory.systemPrompt(
                new DataScope("u-1", "student", Set.of(1L), 1L, "2023001", Set.of("campus")));

        assertThat(prompt)
                .contains("本人学号 2023001")
                .contains("不得")
                .contains("不存在")
                .doesNotContain("{{data_scope}}");
    }

    @Test
    void adminPromptIsMarkedUnrestricted() {
        String prompt = factory.systemPrompt(DataScope.admin("admin-1"));

        assertThat(prompt).contains("不需要按调用者过滤");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=HarnessAgentFactoryPromptTest test`
Expected: FAIL — `systemPrompt(DataScope)` does not exist.

- [ ] **Step 3: Implement the scoped factory**

```java
public HarnessAgent create(Model model, DataScope scope) {
    // existing builder, but .sysPrompt(systemPrompt(scope))
}

String systemPrompt(DataScope scope) {
    String block = DataScopeDescriber.promptBlock(scope);
    if (StringUtils.hasText(properties.getSysPrompt())) {
        return properties.getSysPrompt() + "\n\n## 数据范围\n\n" + block;
    }
    return prompts.render(properties.getSysPromptFile(), Map.of("data_scope", block));
}
```

Remove the old `create(Model)` overload and update `Text2SqlAgentService.stream`:
`agentFactory.create(model, scope)`.

- [ ] **Step 4: Run the factory tests**

Run: `mvn -q -Dtest='HarnessAgentFactoryPromptTest,HarnessAgentFactorySubagentsTest' test`
Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add text2sql-backend/src/main/java/com/nekocong/text2sql/config/HarnessAgentFactory.java text2sql-backend/src/main/java/com/nekocong/text2sql/service/Text2SqlAgentService.java text2sql-backend/src/test/java/com/nekocong/text2sql/config/HarnessAgentFactoryPromptTest.java
git commit -m "feat(agent): build the agent with the caller's data scope"
```

---

### Task 5: `query_database` returns the scope envelope

**Files:**
- Create: `text2sql-backend/src/main/java/com/nekocong/text2sql/security/DataScopeView.java`
- Create: `text2sql-backend/src/main/java/com/nekocong/text2sql/tool/ScopedQueryResult.java`
- Modify: `text2sql-backend/src/main/java/com/nekocong/text2sql/tool/SqlTools.java`
- Test: `text2sql-backend/src/test/java/com/nekocong/text2sql/tool/SqlToolsTest.java`

**Interfaces:**
- Produces: `DataScopeView.of(DataScope)` → `{ boolean restricted, String role, String summary }`; `ScopedQueryResult(Text2SqlResult result, DataScopeView dataScope)`.

- [ ] **Step 1: Write the failing assertions**

In `SqlToolsTest.queryDatabasePassesTheCallerScopeThrough`, add:

```java
assertThat(json)
        .contains("\"dataScope\"")
        .contains("\"restricted\":true")
        .contains("仅本人");
```

Add a new test:

```java
@Test
void adminQueryReportsAnUnrestrictedScope() {
    String json = tools(new AtomicReference<>())
            .queryDatabase("全校平均分", null, DataScope.admin("admin-1"), ExecutionListener.noop());

    assertThat(json).contains("\"restricted\":false").contains("全量");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=SqlToolsTest#queryDatabasePassesTheCallerScopeThrough test`
Expected: FAIL — the JSON has no `dataScope`.

- [ ] **Step 3: Implement the envelope**

```java
public record DataScopeView(boolean restricted, String role, String summary) {
    public static DataScopeView of(DataScope scope) {
        return new DataScopeView(
                DataScopeDescriber.restricted(scope),
                scope == null ? "unknown" : scope.role(),
                DataScopeDescriber.summary(scope));
    }
}

public record ScopedQueryResult(Text2SqlResult result, DataScopeView dataScope) {}
```

In `SqlTools.queryDatabase`, serialize `new ScopedQueryResult(result, DataScopeView.of(scope))` instead of `result`.

- [ ] **Step 4: Run the tool tests**

Run: `mvn -q -Dtest=SqlToolsTest test`
Expected: BUILD SUCCESS, including the existing scope-passthrough and metadata tests.

- [ ] **Step 5: Commit**

```bash
git add text2sql-backend/src/main/java/com/nekocong/text2sql/security/DataScopeView.java text2sql-backend/src/main/java/com/nekocong/text2sql/tool/ScopedQueryResult.java text2sql-backend/src/main/java/com/nekocong/text2sql/tool/SqlTools.java text2sql-backend/src/test/java/com/nekocong/text2sql/tool/SqlToolsTest.java
git commit -m "feat(agent): include dataScope in query tool results"
```

---

### Task 6: Tell the `sql_runner` subagent about the envelope

**Files:**
- Modify: `text2sql-backend/src/main/java/com/nekocong/text2sql/config/HarnessAgentFactory.java`
- Test: `text2sql-backend/src/test/java/com/nekocong/text2sql/config/HarnessAgentFactorySubagentsTest.java`

**Interfaces:**
- Consumes: `ScopedQueryResult` shape from Task 5.

- [ ] **Step 1: Add the failing assertion**

```java
assertThat(declarations)
        .filteredOn(d -> "sql_runner".equals(d.getName()))
        .singleElement()
        .satisfies(d -> assertThat(d.getInlineAgentsBody()).contains("dataScope").contains("result"));
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=HarnessAgentFactorySubagentsTest test`
Expected: FAIL — the body does not mention `dataScope`.

- [ ] **Step 3: Update the declaration**

Change the `sql_runner` body to state: read SQL and rows from `result`, and treat `dataScope` as the authoritative range for the interpretation.

- [ ] **Step 4: Run the test**

Run: `mvn -q -Dtest=HarnessAgentFactorySubagentsTest test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add text2sql-backend/src/main/java/com/nekocong/text2sql/config/HarnessAgentFactory.java text2sql-backend/src/test/java/com/nekocong/text2sql/config/HarnessAgentFactorySubagentsTest.java
git commit -m "docs(agent): describe the scoped tool envelope to sql_runner"
```

---

### Task 7: Deterministic scope clarification

**Files:**
- Create: `text2sql-backend/src/main/java/com/nekocong/text2sql/security/ScopeAnswerGuard.java`
- Test: `text2sql-backend/src/test/java/com/nekocong/text2sql/security/ScopeAnswerGuardTest.java`

**Interfaces:**
- Produces: `public static Optional<String> clarification(DataScope scope, String answer)`.

- [ ] **Step 1: Write the failing test**

```java
package com.nekocong.text2sql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ScopeAnswerGuardTest {

    private static final DataScope STUDENT =
            new DataScope("u-1", "student", Set.of(1L), 1L, "2023001", Set.of("campus"));

    @Test
    void clarifiesAbsenceClaimsForRestrictedScopes() {
        assertThat(ScopeAnswerGuard.clarification(STUDENT, "2 班不存在，数据库只有 1 班"))
                .hasValueSatisfying(note -> assertThat(note)
                        .contains("数据范围仅限")
                        .contains("不能据此判断")
                        .doesNotContain("2 班存在"));
        assertThat(ScopeAnswerGuard.clarification(STUDENT, "没有找到相关记录")).isPresent();
        assertThat(ScopeAnswerGuard.clarification(STUDENT, "无数据")).isPresent();
    }

    @Test
    void leavesNeutralOrAlreadyScopedAnswersAlone() {
        assertThat(ScopeAnswerGuard.clarification(STUDENT, "本班数学平均分 89.5")).isEmpty();
        assertThat(ScopeAnswerGuard.clarification(STUDENT, "当前数据范围仅限本人，2 班不可见")).isEmpty();
    }

    @Test
    void neverClarifiesForAdmins() {
        assertThat(ScopeAnswerGuard.clarification(DataScope.admin("admin-1"), "2 班不存在")).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=ScopeAnswerGuardTest test`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement the guard**

Use the absence phrases `不存在`, `没有数据`, `数据库中只有`, `未找到`, `没有找到`, `无数据`.
Return empty when the scope is unrestricted, the answer is blank, the answer already contains `数据范围`, or no phrase matches.
Otherwise return:

```text
（说明：当前账号的数据范围仅限{summary}；未返回的数据可能在该范围之外，不能据此判断数据库中不存在。）
```

- [ ] **Step 4: Run the test**

Run: `mvn -q -Dtest=ScopeAnswerGuardTest test`
Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add text2sql-backend/src/main/java/com/nekocong/text2sql/security/ScopeAnswerGuard.java text2sql-backend/src/test/java/com/nekocong/text2sql/security/ScopeAnswerGuardTest.java
git commit -m "feat(security): add a deterministic data-scope clarification"
```

---

### Task 8: Append the clarification to the agent stream

**Files:**
- Modify: `text2sql-backend/src/main/java/com/nekocong/text2sql/controller/Text2SqlController.java`
- Test: `text2sql-backend/src/test/java/com/nekocong/text2sql/web/AgentScopeAnswerStreamTest.java`

**Interfaces:**
- Consumes: `ScopeAnswerGuard.clarification` and `TextBlockDeltaEvent.getDelta()`.

- [ ] **Step 1: Write the failing test**

```java
package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.controller.AgentEventPresenter;
import com.nekocong.text2sql.controller.Text2SqlController;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.DataScopeResolver;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.service.Text2SqlAgentService;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AgentScopeAnswerStreamTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void appendsTheScopeClarificationBeforeDone() {
        List<ServerSentEvent<String>> events = stream(
                new DataScope("u-1", "student", Set.of(1L), 1L, "2023001", Set.of("campus")),
                "2 班不存在");

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("delta", "delta", "done");
        assertThat(events.get(1).data()).contains("数据范围仅限").contains("仅本人");
        assertThat(events.get(2).data()).isEqualTo("[DONE]");
    }

    @Test
    void adminAnswersKeepASingleDelta() {
        List<ServerSentEvent<String>> events = stream(DataScope.admin("admin-1"), "2 班不存在");

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("delta", "done");
    }

    private List<ServerSentEvent<String>> stream(DataScope scope, String answer) {
        DataScopeResolver resolver = mock(DataScopeResolver.class);
        when(resolver.resolve(scope.userId(), scope.role())).thenReturn(Mono.just(scope));
        Text2SqlAgentService agent = new Text2SqlAgentService(null, null) {
            @Override
            public Flux<AgentEvent> stream(String message, String sessionId, DataScope callerScope) {
                // The 3-arg constructor is (replyId, blockId, delta).
                return Flux.<AgentEvent>just(new TextBlockDeltaEvent("reply", "block", answer));
            }
        };
        Text2SqlController controller = new Text2SqlController(
                null, agent, null, resolver, new AgentEventPresenter(mapper), mapper);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(scope.userId());
        when(jwt.getClaimAsString(Roles.CLAIM)).thenReturn(scope.role());
        return controller.agentStream(jwt, "1班和2班的数学平均分", "s1").collectList().block();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AgentScopeAnswerStreamTest test`
Expected: FAIL — only one delta is emitted.

- [ ] **Step 3: Wire the guard into `agentStream`**

Accumulate `TextBlockDeltaEvent.getDelta()` while mapping the stream, and concatenate the clarification before `done`:

```java
.flatMapMany(scope -> {
    StringBuilder answer = new StringBuilder();
    Flux<ServerSentEvent<String>> body = agentService.stream(message, scopedSessionId, scope)
            .doOnNext(event -> {
                if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                    answer.append(delta.getDelta());
                }
            })
            .flatMap(event -> Mono.justOrEmpty(presenter.present(event)));
    // defer(): the guard must read the completed answer, not the empty buffer built at assembly time.
    Flux<ServerSentEvent<String>> note = Flux.defer(() ->
            ScopeAnswerGuard.clarification(scope, answer.toString())
                    .map(text -> sse("delta", text))
                    .map(Flux::just)
                    .orElseGet(Flux::empty));
    return Flux.concat(body, note);
})
.concatWith(Mono.just(sse("done", "[DONE]")))
```

`TextBlockDeltaEvent` is already imported in the controller.

- [ ] **Step 4: Run the stream test**

Run: `mvn -q -Dtest=AgentScopeAnswerStreamTest test`
Expected: BUILD SUCCESS, 2 tests pass; `done` is last.

- [ ] **Step 5: Commit**

```bash
git add text2sql-backend/src/main/java/com/nekocong/text2sql/controller/Text2SqlController.java text2sql-backend/src/test/java/com/nekocong/text2sql/web/AgentScopeAnswerStreamTest.java
git commit -m "feat(agent): clarify scope-limited answers in the stream"
```

---

### Task 9: Docs and full verification

**Files:**
- Modify: `docs/nl2sql.md`

- [ ] **Step 1: Document the behavior**

Add a subsection under the Agent section: the agent receives `DataScope` via runtime context, the prompt carries a canonical scope block, `query_database` returns `{result, dataScope}`, and a deterministic clarification is appended when a restricted answer claims absence. Mention that admin answers get no disclaimer.

- [ ] **Step 2: Run the full backend suite**

Run: `mvn test`
Expected: `BUILD SUCCESS`, no failures. New tests: `DataScopeDescriberTest` (3), `HarnessAgentFactoryPromptTest` (2), `ScopeAnswerGuardTest` (3), `AgentScopeAnswerStreamTest` (2), plus the extended `PromptCatalogTest`/`SqlToolsTest` cases.

- [ ] **Step 3: Manual end-to-end (student)**

Deploy or run the backend with a model key, log in as `student01`, switch to Agent, ask 「1班和2班的数学平均分」. Expect: only the student's range is computed, and the answer says the range is limited to the caller — no 「2班不存在」/「数据库只有1班」.

- [ ] **Step 4: Manual regression (pipeline + admin)**

- Same student in pipeline mode: the explanation still ends with 「结果已按当前账号的数据范围（仅本人）过滤」.
- `admin` in Agent mode asks the same question: no scope disclaimer is appended.

- [ ] **Step 5: Commit**

```bash
git add docs/nl2sql.md
git commit -m "docs(agent): document data-scope disclosure and clarification"
```

---

## Workflow follow-up

- After the change is archived, confirm `openspec/specs/data-permission/spec.md` contains the new Agent scope requirements.
