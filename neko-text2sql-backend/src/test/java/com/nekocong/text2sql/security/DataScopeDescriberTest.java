package com.nekocong.text2sql.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.access.DataScopePolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The range description is shared by the deterministic pipeline and the chat agent, so it must read
 * correctly for each role and must only ever mention the caller's own identifiers: no tables, no
 * class names, no other students' numbers.
 */
class DataScopeDescriberTest {

    private final DataScopeDescriber describer = new DataScopeDescriber();

    @Test
    void aStudentIsDescribedAsSeeingOnlyItsOwnRows() {
        DataScope student = AccessFixtures.student();

        assertThat(describer.restricted(student)).isTrue();
        assertThat(describer.summary(student)).isEqualTo("学生（仅本人：学号 2023001，班级 1）");
        // The prompt block keeps the pipeline's original wording verbatim (the role label is the
        // effective role display name, which is the role code for the seeded roles).
        assertThat(describer.promptBlock(student))
                .isEqualTo(
                        "student：本人学号 2023001，所属班级 id [1]。问题中的“我/我的”指该学生本人，"
                                + "用 student_no = '2023001' 过滤。");
    }

    @Test
    void aCounselorIsDescribedByTheClassesItManages() {
        DataScope counselor = AccessFixtures.counselor();

        assertThat(describer.restricted(counselor)).isTrue();
        assertThat(describer.summary(counselor)).isEqualTo("辅导员（仅限所管班级：1、2）");
        assertThat(describer.promptBlock(counselor))
                .isEqualTo("counselor：管理班级 id [1, 2]。问题中的“我管理的班/本班”指这些班级，用 class_id IN (...) 过滤。");
    }

    @Test
    void anAdminIsUnrestricted() {
        DataScope admin = AccessFixtures.admin();

        assertThat(describer.restricted(admin)).isFalse();
        assertThat(describer.summary(admin)).isEqualTo("全量数据，不受范围限制");
        assertThat(describer.promptBlock(admin)).isEqualTo("管理员：可见全部数据，不需要按调用者过滤。");
    }

    @Test
    void restrictedSummariesNeverLeakOutOfRangeObjects() {
        List<String> tableNames =
                List.of("class", "course", "score", "attendance", "teacher", "salary");

        for (DataScope scope : List.of(AccessFixtures.student(), AccessFixtures.counselor())) {
            String summary = describer.summary(scope);

            for (String table : tableNames) {
                assertThat(summary).doesNotContain(table);
            }
            // Only the caller's own classes appear: a counselor manages 1 and 2, never 3.
            assertThat(summary).doesNotContain("班级 3").doesNotContain("、3");
        }
        // The student's summary names only its own student number and class.
        assertThat(describer.summary(AccessFixtures.student()))
                .contains("2023001")
                .doesNotContain("2023002");
        // The block is the pipeline's original wording: it names the caller's own ids and none
        // else. (It legitimately mentions student_no, the caller's own column.)
        assertThat(describer.promptBlock(AccessFixtures.student())).doesNotContain("2023002");
        assertThat(describer.promptBlock(AccessFixtures.counselor()))
                .doesNotContain("2023")
                .doesNotContain("班级 3");
    }

    @Test
    void aScopeWithoutResolvedAccessFailsClosedAndResolvesFirstPersonQuestions() {
        DataScope unresolved = new DataScope("u-x", "student", Set.of(1L), 1L, "2023001", Set.of());

        assertThat(describer.restricted(unresolved)).isTrue();
        assertThat(describer.summary(unresolved)).isEqualTo("无可访问的数据范围");
        assertThat(describer.promptBlock(unresolved)).isEqualTo("当前账号未解析出数据范围，只能看到空结果。");
        assertThat(describer.restricted(null)).isTrue();
    }

    @Test
    void aCustomRoleWithAClassFilterIsDescribedByItsPolicy() {
        DataScope teacher = AccessFixtures.custom(
                "teacher",
                List.of(AccessFixtures.rule(
                        "t1", AccessFixtures.CAMPUS, "score", DataScopePolicy.FILTER, "class_id", "classIds")),
                Set.of(1L));

        assertThat(describer.summary(teacher)).isEqualTo("teacher（仅限所管班级：1）");
        assertThat(describer.promptBlock(teacher)).contains("管理班级 id [1]");
    }
}
