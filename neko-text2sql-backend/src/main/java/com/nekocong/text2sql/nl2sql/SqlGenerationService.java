package com.nekocong.text2sql.nl2sql;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Turns a schema + question into SQL using a (caller-resolved) AgentScope model.
 *
 * <p>This is the deterministic generation step of the NL2SQL pipeline — it does not use an agent
 * loop. The model is resolved per request (the user's configured model, else the global default), so
 * this service only needs the schema, question and dialect.
 */
@Service
public class SqlGenerationService {

    private final PromptAssembler promptAssembler;
    private final SqlResultParser parser;

    /**
     * Creates the service.
     *
     * @param promptAssembler the prompt builder
     * @param parser the tolerant result parser
     */
    public SqlGenerationService(PromptAssembler promptAssembler, SqlResultParser parser) {
        this.promptAssembler = promptAssembler;
        this.parser = parser;
    }

    /**
     * Generates a SQL statement for a question using the given model.
     *
     * @param model the model to call
     * @param dialect the SQL dialect name
     * @param schema the prompt-ready schema DDL
     * @param question the natural-language question
     * @param callerContext a short description of who is asking and what they may see
     * @return a mono emitting the parsed generation
     */
    public Mono<SqlGeneration> generate(
            Model model, String dialect, String schema, String question, String callerContext) {
        List<Msg> messages =
                List.of(
                        new SystemMessage(promptAssembler.systemPrompt(dialect)),
                        new UserMessage(
                                promptAssembler.userPrompt(schema, question, dialect, callerContext)));
        GenerateOptions options = GenerateOptions.builder().temperature(0.0).build();
        return model.stream(messages, List.of(), options)
                .map(SqlGenerationService::textOf)
                .collect(StringBuilder::new, StringBuilder::append)
                .map(StringBuilder::toString)
                .map(parser::parse);
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                sb.append(textBlock.getText());
            }
        }
        return sb.toString();
    }
}
