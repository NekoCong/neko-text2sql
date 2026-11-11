package com.nekocong.text2sql.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The guard is the deterministic backstop: a restricted answer that asserts "there is no such data"
 * gets the range clarification appended, while an answer that already explains the range, an answer
 * with no negative claim, and an administrator's answer are left alone.
 */
class ScopeAnswerGuardTest {

    private final ScopeAnswerGuard guard = new ScopeAnswerGuard();

    @Test
    void aNegativeAssertionFromARestrictedAccountGetsTheClarification() {
        String clarification = guard.clarification(AccessFixtures.student(), "2 班不存在相关成绩。");

        assertThat(clarification)
                .isEqualTo(
                        "（说明：当前账号的数据范围仅限学生（仅本人：学号 2023001，班级 1）；"
                                + "未返回的数据可能在该范围之外，不能据此判断数据库中不存在。）");
        assertThat(guard.clarification(AccessFixtures.counselor(), "3 班没有找到数据"))
                .contains("辅导员（仅限所管班级：1、2）");
        assertThat(guard.clarification(AccessFixtures.student(), "数据库中只有 1 班的记录")).isNotNull();
    }

    @Test
    void anAnswerWithoutANegativeAssertionIsLeftAlone() {
        assertThat(guard.clarification(AccessFixtures.student(), "本次查询返回 3 行，平均分 87.5。"))
                .isNull();
        assertThat(guard.clarification(AccessFixtures.student(), "")).isNull();
        assertThat(guard.clarification(AccessFixtures.student(), null)).isNull();
    }

    @Test
    void anAnswerThatAlreadyExplainsTheRangeIsNotDuplicated() {
        assertThat(
                        guard.clarification(
                                AccessFixtures.student(),
                                "2 班数据不在当前账号的数据范围内，因此看不到；我没有结论。"))
                .isNull();
        assertThat(guard.clarification(AccessFixtures.counselor(), "只能覆盖我管理班级，其余班级不可见"))
                .isNull();
    }

    @Test
    void anAdminsAnswerNeverGetsAClarification() {
        assertThat(guard.clarification(AccessFixtures.admin(), "2 班不存在相关成绩。")).isNull();
    }

    @Test
    void theClarificationNeverClaimsThatOutOfRangeDataExists() {
        String clarification = guard.clarification(AccessFixtures.student(), "2 班无数据。");

        assertThat(clarification).contains("可能在该范围之外").doesNotContain("2 班存在");
        assertThat(clarification).startsWith("（说明：");
    }
}
