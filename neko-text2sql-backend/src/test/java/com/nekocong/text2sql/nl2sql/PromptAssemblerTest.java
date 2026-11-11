package com.nekocong.text2sql.nl2sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.prompt.PromptCatalog;
import org.junit.jupiter.api.Test;

/**
 * The caller context must reach the generation prompt: otherwise the model cannot resolve “我的成绩”
 * to the caller's own identifiers and may invent a name filter.
 */
class PromptAssemblerTest {

    private final PromptAssembler assembler = new PromptAssembler(new PromptCatalog());

    @Test
    void userPromptCarriesSchemaQuestionAndCallerContext() {
        String prompt =
                assembler.userPrompt(
                        "CREATE TABLE student (id BIGINT)",
                        "我的数学成绩是多少？",
                        "MYSQL",
                        "学生：本人学号 2023001。");

        assertThat(prompt).contains("CREATE TABLE student");
        assertThat(prompt).contains("我的数学成绩是多少？");
        assertThat(prompt).contains("MYSQL");
        assertThat(prompt).contains("学生：本人学号 2023001。");
    }
}
