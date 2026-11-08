package com.nekocong.text2sql.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nekocong.text2sql.access.DataScopePolicy;
import com.nekocong.text2sql.security.AccessFixtures;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.SqlPermissionException;
import com.nekocong.text2sql.sql.model.QueryResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end verification of row-level permission against the seeded H2 campus database: the
 * rewritten SQL is actually executed, and the rows prove that a student sees only itself and a
 * counselor only its classes.
 */
@SpringBootTest
class DataPermissionIntegrationTest {

    @Autowired
    private DataScopeRewriter rewriter;

    @Autowired
    private SqlSafetyValidator safetyValidator;

    @Autowired
    private SqlExecutor executor;

    private QueryResult run(String sql, DataScope scope) {
        String safe = safetyValidator.validateAndNormalize(sql);
        return executor.executeBlocking("campus", rewriter.rewrite(safe, scope, "campus"));
    }

    @Test
    void studentOnlySeesItselfEvenWithoutAWhereClause() {
        QueryResult result = run("SELECT student_no, name FROM student", studentScope());

        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.rows().get(0)).containsEntry("student_no", "2023001");
    }

    @Test
    void studentCannotReachAnotherStudentByAskingForIt() {
        QueryResult result =
                run("SELECT student_no FROM student WHERE student_no = '2023002'", studentScope());

        assertThat(result.rowCount()).isZero();
    }

    @Test
    void studentAggregatesAreComputedOverItsOwnRowsOnly() {
        QueryResult result = run("SELECT AVG(score) AS avg_score FROM score", studentScope());

        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(((Number) result.rows().get(0).get("avg_score")).doubleValue())
                .isCloseTo(86.125, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void studentJoinCannotWidenTheScope() {
        QueryResult result =
                run(
                        "SELECT s.student_no FROM score sc JOIN student s ON s.id = sc.student_id",
                        studentScope());

        assertThat(result.rowCount()).isPositive();
        assertThat(result.rows())
                .allSatisfy(row -> assertThat(row).containsEntry("student_no", "2023001"));
    }

    @Test
    void counselorSeesExactlyItsTwoClasses() {
        QueryResult result =
                run(
                        "SELECT class_id, COUNT(*) AS n FROM score GROUP BY class_id ORDER BY class_id",
                        counselorScope());

        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.rows()).extracting(row -> ((Number) row.get("class_id")).intValue())
                .containsExactly(1, 2);
    }

    @Test
    void adminIsUnrestricted() {
        QueryResult result = run("SELECT COUNT(*) AS n FROM class", AccessFixtures.admin());

        assertThat(((Number) result.rows().get(0).get("n")).intValue()).isEqualTo(3);
    }

    @Test
    void unionBypassAttemptStillYieldsOnlyTheStudentsRow() {
        QueryResult result =
                run("SELECT student_no FROM student UNION SELECT student_no FROM student", studentScope());

        assertThat(result.rowCount()).isEqualTo(1);
    }

    @Test
    void tableOutsideTheWhitelistIsRejectedBeforeExecution() {
        assertThatThrownBy(() -> run("SELECT * FROM salary", studentScope()))
                .isInstanceOf(SqlPermissionException.class);
    }

    @Test
    void aConfiguredDenyIsRejectedEvenForACustomRole() {
        // A custom role whose policy denies the table: the statement never reaches the executor.
        DataScope denied = AccessFixtures.custom(
                "reviewer",
                List.of(AccessFixtures.rule(
                        "d1", "campus", "student", DataScopePolicy.DENY, null, null)),
                Set.of(1L));

        assertThatThrownBy(() -> run("SELECT student_no FROM student", denied))
                .isInstanceOf(SqlPermissionException.class)
                .hasMessageContaining("student");
    }

    @Test
    void seededFactsAgreeWithTheirStudentsClass() {
        QueryResult mismatched =
                run(
                        "SELECT COUNT(*) AS n FROM score sc JOIN student s ON s.id = sc.student_id "
                                + "WHERE sc.class_id <> s.class_id",
                        AccessFixtures.admin());

        assertThat(((Number) mismatched.rows().get(0).get("n")).intValue()).isZero();
    }

    private static DataScope studentScope() {
        return AccessFixtures.student();
    }

    private static DataScope counselorScope() {
        return AccessFixtures.counselor();
    }
}
