package com.nekocong.text2sql.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.nl2sql.PromptAssembler;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies prompt templates load from {@code resources/prompts/} and render with placeholders.
 */
class PromptCatalogTest {

    private final PromptCatalog catalog = new PromptCatalog();
    private final PromptAssembler assembler = new PromptAssembler(catalog);

    @Test
    void rendersNl2SqlSystemPrompt() {
        String prompt = assembler.systemPrompt("H2");
        assertThat(prompt).contains("H2").contains("JSON");
        // The final answer must be Chinese.
        assertThat(prompt).contains("简体中文");
        assertThat(prompt).doesNotContain("{{dialect}}");
    }

    @Test
    void rendersUserPromptWithPlaceholders() {
        String prompt =
                assembler.userPrompt(
                        "CREATE TABLE t (id INT)", "统计订单数量", "MYSQL", "管理员：可见全部数据。");
        assertThat(prompt).contains("CREATE TABLE t (id INT)").contains("统计订单数量").contains("MYSQL");
        assertThat(prompt).contains("管理员：可见全部数据。");
        assertThat(prompt).doesNotContain("{{");
    }

    @Test
    void loadsAgentSystemPrompt() {
        String prompt =
                new PromptCatalog()
                        .render(
                                "prompts/agent-system.md",
                                Map.of("data_scope", "学生：本人学号 2023001。"));
        assertThat(prompt).contains("query_database").contains("简体中文");
        assertThat(prompt).contains("学生：本人学号 2023001。");
        assertThat(prompt).doesNotContain("{{");
    }

    @Test
    void theAgentPromptDeclaresTheScopePlaceholderAndTheAnswerRules() {
        String prompt = new PromptCatalog().render("prompts/agent-system.md");

        // The scope is injected per request, never hard-coded.
        assertThat(prompt).contains("{{data_scope}}");
        // Rule 1: a permission filter is never reported as missing data.
        assertThat(prompt).contains("数据库中不存在").contains("当前数据范围内没有可见记录");
        // Rule 2: never probe for out-of-range existence.
        assertThat(prompt).contains("存在性检查");
        // Rule 3: an administrator gets no disclaimer.
        assertThat(prompt).contains("免责声明");
        // The tool envelope: SQL and rows live under result, the range under dataScope.
        assertThat(prompt).contains("result").contains("dataScope");
    }
}
