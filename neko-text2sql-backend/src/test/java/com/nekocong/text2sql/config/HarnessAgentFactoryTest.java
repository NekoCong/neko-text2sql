package com.nekocong.text2sql.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.prompt.PromptCatalog;
import com.nekocong.text2sql.security.AccessFixtures;
import org.junit.jupiter.api.Test;

/**
 * Every agent is built with the caller's data range in its system prompt, rendered per request. No
 * scope-less overload exists, so a caller cannot forget it; the rendered prompt must contain the
 * range and no leftover placeholder.
 */
class HarnessAgentFactoryTest {

    private final PromptCatalog prompts = new PromptCatalog();

    @Test
    void aStudentPromptContainsItsOwnRangeOnly() {
        String prompt = factory().systemPrompt(AccessFixtures.student());

        assertThat(prompt).contains("本人学号").contains("2023001");
        assertThat(prompt).doesNotContain("{{data_scope}}");
        assertThat(prompt).doesNotContain("{{");
        assertThat(prompt).doesNotContain("2023002");
        assertThat(prompt).doesNotContain("管理班级 id");
    }

    @Test
    void anAdminPromptSaysTheRangeIsUnrestricted() {
        String prompt = factory().systemPrompt(AccessFixtures.admin());

        assertThat(prompt).contains("管理员：可见全部数据");
        assertThat(prompt).doesNotContain("{{data_scope}}");
        assertThat(prompt).doesNotContain("仅本人");
    }

    @Test
    void eachRequestIsRenderedForItsOwnCaller() {
        HarnessAgentFactory factory = factory();

        assertThat(factory.systemPrompt(AccessFixtures.counselor()))
                .contains("管理班级 id [1, 2]")
                // No range from the previous caller survives: the prompt is rebuilt per request.
                .doesNotContain("2023001");
        assertThat(factory.systemPrompt(AccessFixtures.student())).contains("2023001");
    }

    @Test
    void thePromptCarriesTheThreeAnswerRules() {
        String prompt = factory().systemPrompt(AccessFixtures.student());

        assertThat(prompt).contains("绝不").contains("数据库中不存在");
        assertThat(prompt).contains("禁止").contains("存在性检查");
        assertThat(prompt).contains("免责声明");
    }

    private HarnessAgentFactory factory() {
        return new HarnessAgentFactory(new AgentProperties(), null, prompts);
    }
}
