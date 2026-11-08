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
        String sql = rewriter.rewrite("SELECT student_no FROM student", student());

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
                        student());

        assertThat(sql).containsIgnoringCase("AS sc").containsIgnoringCase("AS s");
        assertThat(sql).containsIgnoringCase("student_id IN (1)");
        assertThat(sql).containsIgnoringCase("student_no IN ('2023001')");
    }

    @Test
    void filtersEveryBranchOfAUnion() {
        String sql =
                rewriter.rewrite(
                        "SELECT student_no FROM student UNION SELECT student_no FROM student", student());

        assertThat(countOccurrences(sql.toLowerCase(), "student_no in ('2023001')")).isEqualTo(2);
    }

    @Test
    void filtersSubqueriesInsideExpressions() {
        String sql =
                rewriter.rewrite(
                        "SELECT name FROM student WHERE class_id IN (SELECT class_id FROM student)",
                        student());

        assertThat(countOccurrences(sql, "SELECT * FROM student")).isEqualTo(2);
    }

    @Test
    void rewritesCteBodiesButLeavesCteReferencesAlone() {
        String sql =
                rewriter.rewrite(
                        "WITH x AS (SELECT * FROM score) SELECT * FROM x", student());

        assertThat(sql).containsIgnoringCase("student_id IN (1)");
        // The outer reference is a CTE, not a physical table, so it must not be wrapped again.
        assertThat(sql).doesNotContainIgnoringCase("AS x");
    }

    @Test
    void counselorFiltersByManagedClasses() {
        String sql =
                rewriter.rewrite("SELECT class_id, count(*) FROM score GROUP BY class_id", counselor());

        assertThat(compact(sql)).contains("class_id in (1,2)");
        assertThat(compact(sql)).doesNotContain("student_id in");
    }

    @Test
    void adminStatementIsReturnedUnchanged() {
        String sql = "SELECT * FROM student";

        assertThat(rewriter.rewrite(sql, admin())).isEqualTo(sql);
    }

    @Test
    void rejectsTablesOutsideTheWhitelist() {
        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM salary", student()))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("salary");
    }

    @Test
    void rejectsUnparsableSql() {
        assertThatThrownBy(() -> rewriter.rewrite("SELECT FROM", student()))
                .isInstanceOf(SqlPermissionException.class);
    }

    @Test
    void filtersExistsSubqueries() {
        String sql =
                rewriter.rewrite(
                        "SELECT name FROM student WHERE EXISTS (SELECT 1 FROM score)", student());

        assertThat(compact(sql)).contains("student_id in (1)");
        assertThat(compact(sql)).contains("student_no in ('2023001')");
    }

    @Test
    void rewritesParenthesizedSelectOrderBy() {
        String sql =
                rewriter.rewrite(
                        "SELECT (SELECT student_no FROM student ORDER BY (SELECT MAX(score) FROM score)) FROM student",
                        student());

        assertThat(compact(sql)).contains("student_id in (1)");
    }

    @Test
    void rejectsValuesQueries() {
        assertThatThrownBy(
                        () -> rewriter.rewrite("WITH v AS (VALUES (1)) SELECT * FROM v", student()))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("VALUES");
    }

    @Test
    void rejectsNonWhitelistedTablesInsideExistsSubqueries() {
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "SELECT name FROM student WHERE EXISTS (SELECT 1 FROM salary)",
                                        student()))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("salary");
    }

    @Test
    void filtersOrderBySubqueriesOfSetOperations() {
        String sql =
                rewriter.rewrite(
                        "SELECT student_no FROM student UNION SELECT student_no FROM student "
                                + "ORDER BY (SELECT MAX(score) FROM score)",
                        student());

        assertThat(compact(sql)).contains("student_id in (1)");
    }

    @Test
    void rejectsNonWhitelistedTablesInsideSetOperationOrderBy() {
        assertThatThrownBy(
                        () ->
                                rewriter.rewrite(
                                        "SELECT student_no FROM student UNION SELECT student_no FROM student "
                                                + "ORDER BY (SELECT score FROM salary)",
                                        student()))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("salary");
    }

    @Test
    void rejectsSelectInto() {
        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM student INTO backup", student()))
                .isInstanceOf(SqlPermissionException.class);
    }

    @Test
    void rewritesNestedCteBodiesOnce() {
        String sql =
                rewriter.rewrite(
                        "WITH a AS (WITH b AS (SELECT * FROM score) SELECT * FROM b) SELECT * FROM a",
                        student());

        assertThat(countOccurrences(compact(sql), "student_id in (1)")).isEqualTo(1);
        assertThat(compact(sql)).doesNotContain("as b").doesNotContain("as a");
    }

    @Test
    void givesRepeatedTableReferencesDistinctAliases() {
        String sql = rewriter.rewrite("SELECT 1 FROM student, student", student());

        assertThat(sql).containsIgnoringCase("AS student_sp1");
    }

    @Test
    void aCustomRoleIsEnforcedFromItsPolicyNotItsName() {
        // The role code "teacher" is unknown to the legacy code; only the DB policy can filter score.
        String sql = rewriter.rewrite("SELECT AVG(score) FROM score", customTeacher());

        assertThat(compact(sql)).contains("class_id in (1)");
        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM student", customTeacher()))
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

        String sql = rewriter.rewrite("SELECT * FROM student", scope);

        assertThat(compact(sql)).contains("(student_no in ('2023002')) or (class_id in (2))");
    }

    @Test
    void quotesValuesAndEscapesSingleQuotes() {
        DataScope scope = AccessFixtures.custom(
                "o'brien",
                List.of(rule("u1", CAMPUS, "student", DataScopePolicy.FILTER, "username", "username")),
                Set.of());

        String sql = rewriter.rewrite("SELECT * FROM student", scope);

        assertThat(sql).contains("'o''brien'");
    }

    @Test
    void aMissingPrincipalValueDeniesInsteadOfRunning() {
        DataScope noStudentId = AccessFixtures.custom(
                "nograde",
                List.of(rule("g1", CAMPUS, "score", DataScopePolicy.FILTER, "student_id", "studentId")),
                Set.of(1L));

        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM score", noStudentId))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("studentId");    }

    @Test
    void anEmptyClassSetDeniesInsteadOfRunning() {
        DataScope noClasses = AccessFixtures.custom(
                "noclass",
                List.of(rule("c1", CAMPUS, "score", DataScopePolicy.FILTER, "class_id", "classIds")),
                Set.of());

        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM score", noClasses))
                .isInstanceOf(SqlPermissionException.class);
    }

    @Test
    void aDeniedTableIsRejectedBeforeAnySqlIsProduced() {
        DataScope denied = AccessFixtures.custom(
                "denied",
                List.of(rule("d1", CAMPUS, "student", DataScopePolicy.DENY, null, null)),
                Set.of(1L));

        assertThatThrownBy(() -> rewriter.rewrite("SELECT name FROM student", denied))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("student");
    }

    @Test
    void aScopeWithoutResolvedAccessIsRejected() {
        DataScope legacy = new DataScope("u", "student", Set.of(1L), 1L, "2023001", Set.of(CAMPUS));

        assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM student", legacy))
                .isInstanceOf(SqlPermissionException.class);
    }

    private static String compact(String sql) {
        return sql.toLowerCase().replaceAll("\\s+", " ").replaceAll(",\\s*", ",");
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
