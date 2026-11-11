package com.nekocong.text2sql.sql;

import static com.nekocong.text2sql.security.AccessFixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nekocong.text2sql.access.DataScopePolicy;
import com.nekocong.text2sql.access.PrincipalAttributes;
import com.nekocong.text2sql.security.AccessFixtures;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.SqlPermissionException;
import com.nekocong.text2sql.security.TablePolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the SQL filter injection: every scoped table reference must end up filtered, in
 * every shape the model is likely to produce, and anything the rewriter cannot handle must be
 * rejected instead of executed. The filters come from the configured access policies only.
 */
class DataScopeRewriterTest {

    private static final String CAMPUS = AccessFixtures.CAMPUS;

    private final DataScopeRewriter rewriter = new DataScopeRewriter(new TablePolicy());

    private static DataScope student() {
        return AccessFixtures.student();
    }

    private static DataScope counselor() {
        return AccessFixtures.counselor();
    }

    private static DataScope admin() {
        return DataScope.from(AccessFixtures.builder("admin", Set.of(), List.of())
                .permissions(Set.of("access.bypass_data_scope"))
                .principal(new PrincipalAttributes("u-admin", "admin", null, null, Set.of()))
                .build());
    }

    private static DataScope customTeacher() {
        return AccessFixtures.custom(
                "teacher",
                List.of(rule("t-score", CAMPUS, "score", DataScopePolicy.FILTER, "class_id", "classIds"),
                        rule("t-course", CAMPUS, "course", DataScopePolicy.ALL, null, null)),
                Set.of(1L));
    }

    @Test
    void filtersATableWithoutAlias() {
        String sql = rewriter.rewrite("SELECT student_no FROM student", student(), CAMPUS);

        assertThat(sql).containsIgnoringCase("student_no IN ('2023001')");
        assertThat(compact(sql)).contains(") as student");
        assertThat(compact(sql)).doesNotContain("_sp2");
    }

    @Test
    void filtersJoinSidesAndKeepsAliases() {
        String sql =
                rewriter.rewrite(
                        "SELECT s.name, avg(sc.score) FROM score sc JOIN student s ON s.id = sc.student_id "
                                + "GROUP BY s.name",
                        student(),
                        CAMPUS);

        assertThat(sql).containsIgnoringCase("AS sc").containsIgnoringCase("AS s");
        assertThat(sql).containsIgnoringCase("student_id IN (1)");
        assertThat(sql).containsIgnoringCase("student_no IN ('2023001')");
    }

    @Test
    void filtersEveryBranchOfAUnion() {
        String sql =
                rewriter.rewrite(
                        "SELECT student_no FROM student UNION SELECT student_no FROM student", student(), CAMPUS);

        assertThat(countOccurrences(sql.toLowerCase(), "student_no in ('2023001')")).isEqualTo(2);
    }

    @Test
    void filtersSubqueriesInsideExpressions() {
        String sql =
                rewriter.rewrite(
                        "SELECT name FROM student WHERE class_id IN (SELECT class_id FROM student)",
                        student(),
                        CAMPUS);

        assertThat(countOccurrences(sql, "SELECT * FROM student")).isEqualTo(2);
    }

    @Test
    void rewritesCteBodiesButLeavesCteReferencesAlone() {
        String sql =
                rewriter.rewrite(
                        "WITH x AS (SELECT * FROM score) SELECT * FROM x", student(), CAMPUS);

        assertThat(sql).containsIgnoringCase("student_id IN (1)");
        // The outer reference is a CTE, not a physical table, so it must not be wrapped again.
        assertThat(sql).doesNotContainIgnoringCase("AS x");
    }

    @Test
    void counselorFiltersByManagedClasses() {
        String sql =
                rewriter.rewrite("SELECT class_id, count(*) FROM score GROUP BY class_id", counselor(), CAMPUS);

        assertThat(compact(sql)).contains("class_id in (1,2)");
        assertThat(compact(sql)).doesNotContain("student_id in");
    }

    @Test
    void adminStatementIsReturnedUnchanged() {
        String sql = "SELECT * FROM student";

        assertThat(rewriter.rewrite(sql, admin(), CAMPUS)).isEqualTo(sql);
    }

    @Test
    void rejectsTablesOutsideTheWhitelist() {
        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM salary", student(), CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("salary");
    }

    @Test
    void rejectsUnparsableSql() {
        assertThatThrownBy(() -> rewriter.rewrite("SELECT FROM", student(), CAMPUS))
                .isInstanceOf(SqlPermissionException.class);
    }

    @Test
    void filtersExistsSubqueries() {
        String sql =
                rewriter.rewrite(
                        "SELECT name FROM student WHERE EXISTS (SELECT 1 FROM score)", student(), CAMPUS);

        assertThat(compact(sql)).contains("student_id in (1)");
        assertThat(compact(sql)).contains("student_no in ('2023001')");
    }

    @Test
    void rewritesParenthesizedSelectOrderBy() {
        String sql =
                rewriter.rewrite(
                        "SELECT (SELECT student_no FROM student ORDER BY (SELECT MAX(score) FROM score)) FROM student",
                        student(),
                        CAMPUS);

        assertThat(compact(sql)).contains("student_id in (1)");
    }

    @Test
    void rejectsValuesQueries() {
        assertThatThrownBy(
                        () -> rewriter.rewrite("WITH v AS (VALUES (1)) SELECT * FROM v", student(), CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("VALUES");
    }

    @Test
    void rejectsNonWhitelistedTablesInsideExistsSubqueries() {
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "SELECT name FROM student WHERE EXISTS (SELECT 1 FROM salary)",
                                        student(),
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("salary");
    }

    @Test
    void filtersOrderBySubqueriesOfSetOperations() {
        String sql =
                rewriter.rewrite(
                        "SELECT student_no FROM student UNION SELECT student_no FROM student "
                                + "ORDER BY (SELECT MAX(score) FROM score)",
                        student(),
                        CAMPUS);

        assertThat(compact(sql)).contains("student_id in (1)");
    }

    @Test
    void rejectsNonWhitelistedTablesInsideSetOperationOrderBy() {
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "SELECT student_no FROM student UNION SELECT student_no FROM student "
                                                + "ORDER BY (SELECT score FROM salary)",
                                        student(),
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("salary");
    }

    @Test
    void rejectsSelectInto() {
        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM student INTO backup", student(), CAMPUS))
                .isInstanceOf(SqlPermissionException.class);
    }

    @Test
    void rewritesNestedCteBodiesOnce() {
        String sql =
                rewriter.rewrite(
                        "WITH a AS (WITH b AS (SELECT * FROM score) SELECT * FROM b) SELECT * FROM a",
                        student(),
                        CAMPUS);

        assertThat(countOccurrences(compact(sql), "student_id in (1)")).isEqualTo(1);
        assertThat(compact(sql)).doesNotContain("as b").doesNotContain("as a");
    }

    @Test
    void givesRepeatedTableReferencesDistinctAliases() {
        String sql = rewriter.rewrite("SELECT 1 FROM student, student", student(), CAMPUS);

        assertThat(sql).containsIgnoringCase("AS student_sp1");
    }

    @Test
    void aCustomRoleIsEnforcedFromItsPolicyNotItsName() {
        // The role code "teacher" is unknown to the legacy code; only the DB policy can filter score.
        String sql = rewriter.rewrite("SELECT AVG(score) FROM score", customTeacher(), CAMPUS);

        assertThat(compact(sql)).contains("class_id in (1)");
        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM student", customTeacher(), CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("student");
    }

    @Test
    void orsEveryPredicateOfAFilteredTable() {
        DataScope scope = AccessFixtures.custom(
                "reviewer",
                List.of(
                        rule("r1", CAMPUS, "student", DataScopePolicy.FILTER, "student_no", "studentNo"),
                        rule("r2", CAMPUS, "student", DataScopePolicy.FILTER, "class_id", "classIds")),
                Set.of(2L),
                "2023002",
                null);

        String sql = rewriter.rewrite("SELECT * FROM student", scope, CAMPUS);

        assertThat(compact(sql)).contains("(student_no in ('2023002')) or (class_id in (2))");
    }

    @Test
    void quotesValuesAndEscapesSingleQuotes() {
        DataScope scope = AccessFixtures.custom(
                "o'brien",
                List.of(rule("u1", CAMPUS, "student", DataScopePolicy.FILTER, "username", "username")),
                Set.of());

        String sql = rewriter.rewrite("SELECT * FROM student", scope, CAMPUS);

        assertThat(sql).contains("'o''brien'");
    }

    @Test
    void aMissingPrincipalValueDeniesInsteadOfRunning() {
        DataScope noStudentId = AccessFixtures.custom(
                "nograde",
                List.of(rule("g1", CAMPUS, "score", DataScopePolicy.FILTER, "student_id", "studentId")),
                Set.of(1L));

        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM score", noStudentId, CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("studentId");    }

    @Test
    void anEmptyClassSetDeniesInsteadOfRunning() {
        DataScope noClasses = AccessFixtures.custom(
                "noclass",
                List.of(rule("c1", CAMPUS, "score", DataScopePolicy.FILTER, "class_id", "classIds")),
                Set.of());

        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM score", noClasses, CAMPUS))
                .isInstanceOf(SqlPermissionException.class);
    }

    @Test
    void aDeniedTableIsRejectedBeforeAnySqlIsProduced() {
        DataScope denied = AccessFixtures.custom(
                "denied",
                List.of(rule("d1", CAMPUS, "student", DataScopePolicy.DENY, null, null)),
                Set.of(1L));

        assertThatThrownBy(() -> rewriter.rewrite("SELECT name FROM student", denied, CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("student");
    }

    @Test
    void aScopeWithoutResolvedAccessIsRejected() {
        DataScope legacy = new DataScope("u", "student", Set.of(1L), 1L, "2023001", Set.of(CAMPUS));

        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM student", legacy, CAMPUS))
                .isInstanceOf(SqlPermissionException.class);
    }

    @Test
    void usesTheExplicitDatasourceIdForACallerWithSeveralGrants() {
        // Two grants: the single-grant fallback cannot guess, so passing the resolved id is what
        // makes the multi-datasource caller usable.
        DataScope multi = multiGrantStudent();
        TablePolicy policy = new TablePolicy();
        assertThat(policy.visibleTables(multi, null)).isEmpty();
        assertThatThrownBy(() -> policy.predicates(multi, null, "student"))
                .isInstanceOf(SqlPermissionException.class);

        String sql = rewriter.rewrite("SELECT student_no FROM student", multi, CAMPUS);

        assertThat(sql).containsIgnoringCase("student_no IN ('2023001')");
    }

    @Test
    void theExplicitDatasourceIdSelectsThatDatasourcesPolicies() {
        // The student policies only exist for campus: the same caller is denied on the other grant.
        DataScope multi = multiGrantStudent();

        assertThatThrownBy(() -> rewriter.rewrite("SELECT student_no FROM student", multi, "archive"))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("student");
    }

    @Test
    void escapesBackslashesAsWellAsQuotes() {
        // The literal renderer is the single place a principal value becomes SQL text. A backslash is
        // doubled so a following quote cannot be reinterpreted as an escape on a MySQL-family parser.
        assertThat(DataScopeRewriter.quote("2023001")).isEqualTo("'2023001'");
        assertThat(DataScopeRewriter.quote("o'brien")).isEqualTo("'o''brien'");
        assertThat(DataScopeRewriter.quote("a\\")).isEqualTo("'a\\\\'");
        assertThat(DataScopeRewriter.quote("a\\'b")).isEqualTo("'a\\\\''b'");
    }

    @Test
    void anAdminIsUnrestrictedEvenOnAnUnknownDatasource() {
        assertThat(rewriter.rewrite("SELECT * FROM student", admin(), "unknown")).isEqualTo(
                "SELECT * FROM student");
    }

    @Test
    void rejectsACteWhoseNameCollidesWithAProtectedTable() {
        // The bypass probe: a CTE named like a protected table must not make the outer reference
        // look like a CTE, which would skip both the whitelist and the row filter.
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "WITH student AS (SELECT * FROM student) SELECT * FROM student",
                                        student(),
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("student");
    }

    @Test
    void rejectsACteNameCollisionEvenWhenTheBodyLooksBenign() {
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "WITH score AS (SELECT 1 AS x) SELECT * FROM score",
                                        student(),
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("score");
    }

    @Test
    void rejectsACteNameCollisionCaseInsensitively() {
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "WITH Student AS (SELECT 1 AS x) SELECT * FROM Student",
                                        student(),
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class);
    }

    @Test
    void filtersAProtectedTableReferencedInsideALegalCteBody() {
        String sql =
                rewriter.rewrite(
                        "WITH mine AS (SELECT * FROM student WHERE class_id = 1) SELECT * FROM mine",
                        student(),
                        CAMPUS);

        assertThat(compact(sql)).contains("student_no in ('2023001')");
        // The outer reference is the CTE itself and must not be wrapped again.
        assertThat(compact(sql)).doesNotContain("as mine");
    }

    @Test
    void rejectsADeniedTableReferencedInsideALegalCteBody() {
        DataScope denied = AccessFixtures.custom(
                "denied",
                List.of(rule("d1", CAMPUS, "student", DataScopePolicy.DENY, null, null)),
                Set.of(1L));

        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "WITH mine AS (SELECT * FROM student) SELECT * FROM mine",
                                        denied,
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("student");
    }

    @Test
    void aSchemaQualifiedCteBodyReferenceIsStillFiltered() {
        String sql =
                rewriter.rewrite(
                        "WITH mine AS (SELECT * FROM campus.student) SELECT * FROM mine",
                        student(),
                        CAMPUS);

        assertThat(compact(sql)).contains("student_no in ('2023001')");
    }

    @Test
    void earlierCtesAreVisibleInsideALaterCteBodyButTheCurrentNameIsNot() {
        // `y` may read `x` (defined earlier), so `x` is not wrapped at the use site, while the
        // physical table in `x`'s body is filtered exactly once.
        String sql =
                rewriter.rewrite(
                        "WITH x AS (SELECT * FROM student), y AS (SELECT * FROM x) "
                                + "SELECT * FROM y",
                        student(),
                        CAMPUS);

        assertThat(countOccurrences(compact(sql), "student_no in ('2023001')")).isEqualTo(1);
        assertThat(compact(sql)).doesNotContain("as x").doesNotContain("as y");
    }

    @Test
    void aCteCannotReferenceItsOwnNameAsAPhysicalTable() {
        // The name `x` is only a CTE from outside its body. Inside its own body `FROM x` is a
        // physical table reference, which the policy denies.
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "WITH x AS (SELECT * FROM x) SELECT * FROM x",
                                        student(),
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("x");
    }

    @Test
    void rejectsRecursiveCtes() {
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "WITH RECURSIVE r AS (SELECT 1) SELECT * FROM r",
                                        student(),
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("RECURSIVE");
    }

    @Test
    void rejectsNestedRecursiveCtes() {
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "WITH a AS (WITH RECURSIVE r AS (SELECT 1) SELECT * FROM r) "
                                                + "SELECT * FROM a",
                                        student(),
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("RECURSIVE");
    }

    @Test
    void rejectsACteNameCollisionInsideANestedWithList() {
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "WITH a AS (WITH score AS (SELECT 1 AS x) SELECT * FROM score) "
                                                + "SELECT * FROM a",
                                        student(),
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("score");
    }

    @Test
    void rejectsACteShadowingADeniedTable() {
        // The collision rule covers every configured table, including DENY-only ones (e.g. a salary
        // table the caller may not see at all): the CTE must not be used as a probe for it.
        DataScope denied = AccessFixtures.custom(
                "denied",
                List.of(rule("d1", CAMPUS, "salary", DataScopePolicy.DENY, null, null),
                        rule("d2", CAMPUS, "student", DataScopePolicy.FILTER, "student_no", "studentNo")),
                Set.of(1L),
                "2023001",
                null);

        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "WITH salary AS (SELECT 1 AS x) SELECT * FROM salary",
                                        denied,
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("salary");
    }

    @Test
    void aSchemaQualifiedReferenceIsNotShadowedByACte() {
        // C1 regression: `Table.getName()` is only `salary`, so matching the base name against the CTE
        // set would return the physical table unfiltered. A qualified reference is always physical.
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "WITH salary AS (SELECT 1 AS x) SELECT * FROM campus.salary",
                                        student(),
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("salary");
    }

    @Test
    void aSchemaQualifiedProtectedTableInACteBodyIsStillFiltered() {
        // The qualified reference appears inside a CTE body whose name does not collide; the physical
        // table must still be filtered rather than taken for the CTE.
        String sql =
                rewriter.rewrite(
                        "WITH mine AS (SELECT * FROM campus.student), other AS (SELECT * FROM mine) "
                                + "SELECT * FROM campus.student",
                        student(),
                        CAMPUS);

        // Two physical references (inside the body and in the outer FROM) -> two filters.
        assertThat(countOccurrences(compact(sql), "student_no in ('2023001')")).isEqualTo(2);
    }

    @Test
    void handlesQuotedCteNamesAndReferences() {
        String sql =
                rewriter.rewrite(
                        "WITH `mine` AS (SELECT * FROM score) SELECT * FROM `mine`", student(), CAMPUS);

        assertThat(compact(sql)).contains("student_id in (1)");
        assertThat(compact(sql)).doesNotContain("as `mine`");
    }

    @Test
    void rejectsACteWhoseQuotedNameCollidesWithAProtectedTable() {
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "WITH `student` AS (SELECT 1 AS x) SELECT * FROM `student`",
                                        student(),
                                        CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("student");
    }

    @Test
    void predicateValuesContainingQuoteAndBackslashNeverDegradeToABareColumn() {
        // I3 regression: a value like a\'b used to make the re-parse return the bare column, turning
        // "only my rows" into "every row". Such values now deny (fail closed) instead of degrading.
        for (String hostile : new String[] {"a\\'b", "it\\'s"}) {
            DataScope scope = scopeWithUsername(hostile);
            assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM student", scope, CAMPUS))
                    .as("value %s", hostile)
                    .isInstanceOf(SqlPermissionException.class)
                    .hasMessageContaining("行过滤条件");
        }

        // A leading/embedded quote without a preceding backslash is unambiguous and still filters.
        DataScope ok = scopeWithUsername("o'brien");
        String sql = rewriter.rewrite("SELECT * FROM student", ok, CAMPUS);
        assertThat(compact(sql)).contains("username in ('o''brien')");
        // The filter did not degrade to a bare column (the old widening bug).
        assertThat(compact(sql)).doesNotContain("where username)");
    }

    @Test
    void aBackslashOnlyValueKeepsTheEscapedLiteral() {
        DataScope scope = scopeWithUsername("a\\b");
        String sql = rewriter.rewrite("SELECT * FROM student", scope, CAMPUS);

        assertThat(compact(sql)).contains("username in ('a\\\\b')");
    }

    private static DataScope scopeWithUsername(String username) {
        return DataScope.from(new com.nekocong.text2sql.access.EffectiveAccess(
                "u-username",
                List.of(),
                Set.of(),
                Set.of(CAMPUS),
                List.of(rule("u1", CAMPUS, "student", DataScopePolicy.FILTER, "username", "username")),
                new PrincipalAttributes("u-username", username, null, null, Set.of())));
    }

    @Test
    void rejectsAnUnsafePredicateColumn() {
        // A malformed rule must deny the table, never render raw identifier text into SQL.
        DataScope unsafe = AccessFixtures.custom(
                "unsafe",
                List.of(
                        rule(
                                "u1",
                                CAMPUS,
                                "student",
                                DataScopePolicy.FILTER,
                                "student_no) OR 1=1 --",
                                "studentNo")),
                Set.of(1L));

        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM student", unsafe, CAMPUS))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("student");
    }

    @Test
    void aStudentCteQueryReturnsNoOutOfScopeRows() {
        // Task 4.3 regression: the filtered columns come from the caller's own attributes; a row
        // filter for another student's number or class must not be produced for this caller.
        String sql =
                rewriter.rewrite(
                        "WITH mine AS (SELECT * FROM student), s AS (SELECT * FROM score) "
                                + "SELECT mine.name FROM mine JOIN s ON s.student_id = mine.id",
                        student(),
                        CAMPUS);

        String compacted = compact(sql);
        assertThat(compacted).contains("student_no in ('2023001')");
        assertThat(compacted).contains("student_id in (1)");
        assertThat(compacted).doesNotContain("'2023002'").doesNotContain("student_id in (2)");
    }

    private static DataScope multiGrantStudent() {
        DataScope base = AccessFixtures.student();
        return DataScope.from(new com.nekocong.text2sql.access.EffectiveAccess(
                base.access().userId(),
                base.access().roles(),
                base.access().permissions(),
                Set.of(CAMPUS, "archive"),
                base.access().dataScopes(),
                base.access().principal()));
    }

    private static String compact(String sql) {        return sql.toLowerCase().replaceAll("\\s+", " ").replaceAll(",\\s*", ",");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        String lowerText = text.toLowerCase();
        String lowerNeedle = needle.toLowerCase();
        while ((index = lowerText.indexOf(lowerNeedle, index)) >= 0) {
            count++;
            index += lowerNeedle.length();
        }
        return count;
    }
}
