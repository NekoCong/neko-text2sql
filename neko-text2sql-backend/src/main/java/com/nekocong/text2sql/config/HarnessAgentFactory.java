package com.nekocong.text2sql.config;

import com.nekocong.text2sql.prompt.PromptCatalog;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.DataScopeDescriber;
import com.nekocong.text2sql.tool.SqlTools;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Creates {@link HarnessAgent} instances on demand.
 *
 * <p>{@code HarnessAgent} (like the underlying {@code ReActAgent}) is not safe for concurrent
 * calls, so web requests build a fresh agent per request. The factory is intentionally stateless
 * apart from immutable configuration.
 *
 * <p>The agent is equipped with the text-to-SQL tools ({@link SqlTools}) so it can answer data
 * questions through {@code query_database}; the shell tool is disabled as it is irrelevant and a
 * needless security surface for a data agent.
 */
@Component
public class HarnessAgentFactory {

    private final AgentProperties properties;
    private final SqlTools sqlTools;
    private final PromptCatalog prompts;
    private final DataScopeDescriber describer = new DataScopeDescriber();

    /**
     * Creates the factory.
     *
     * @param properties agent configuration
     * @param sqlTools the text-to-SQL tools registered on the agent
     * @param prompts the prompt catalog (loads the system prompt from {@code prompts/})
     */
    public HarnessAgentFactory(AgentProperties properties, SqlTools sqlTools, PromptCatalog prompts) {
        this.properties = properties;
        this.sqlTools = sqlTools;
        this.prompts = prompts;
    }

    /**
     * Builds a new agent from the configured properties, using the given model, with the caller's
     * data scope rendered into the system prompt.
     *
     * <p>The scope is a required argument on purpose: there is no scope-less overload, so a caller
     * cannot silently build an agent whose prompt omits (and thus misstates) the caller's data
     * range.
     *
     * @param model the model to use (typically the user's configured model)
     * @param scope the caller's resolved data scope
     * @return a ready-to-use {@link HarnessAgent}
     */
    public HarnessAgent create(Model model, DataScope scope) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(sqlTools);
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name(properties.getName())
                        .sysPrompt(systemPrompt(scope))
                        .model(model)
                        .workspace(Paths.get(properties.getWorkspace()))
                        .maxContextTokens(properties.getMaxContextTokens())
                        .toolkit(toolkit)
                        .disableShellTool();
        if (properties.isSubagentsEnabled()) {
            builder.subagents(subagentDeclarations());
        }
        return builder.build();
    }

    /**
     * Renders the agent system prompt for one caller: the inline override when set, otherwise the
     * prompt file ({@code text2sql.agent.sys-prompt-file}) with {@code {{data_scope}}} replaced by
     * the caller's range description.
     *
     * <p>Per request, never cached, so one caller's range can never be reused for another.
     *
     * @param scope the caller's resolved data scope
     * @return the rendered system prompt
     */
    String systemPrompt(DataScope scope) {
        String scopeBlock = describer.promptBlock(scope);
        return StringUtils.hasText(properties.getSysPrompt())
                ? render(properties.getSysPrompt(), scopeBlock)
                : prompts.render(properties.getSysPromptFile(), Map.of("data_scope", scopeBlock));
    }

    private static String render(String template, String scopeBlock) {
        return template.replace("{{data_scope}}", scopeBlock);
    }

    /**
     * Declares the multi-agent team the parent agent may delegate to (DB-GPT-style planner/executor/
     * reporter split). Exposed separately so it can be verified without a model key or agent build.
     *
     * @return the subagent declarations
     */
    public List<SubagentDeclaration> subagentDeclarations() {
        return List.of(
                SubagentDeclaration.builder()
                        .name("schema_explorer")
                        .description("Explore the database schema (tables and columns).")
                        .inlineAgentsBody(
                                "You explore a SQL database schema. Use list_tables and describe_table to "
                                        + "inspect the structure, then summarise the relevant tables concisely.")
                        .tools(List.of("list_tables", "describe_table"))
                        .build(),
                SubagentDeclaration.builder()
                        .name("sql_runner")
                        .description("Answer a data question by running a read-only SQL query.")
                        .inlineAgentsBody(
                                "You answer data questions. Call query_database with the user's question. "
                                        + "It returns {\"result\": {...}, \"dataScope\": {...}}: read the "
                                        + "SQL and the rows from result.sql and result.result, and the "
                                        + "range from dataScope. Report the returned SQL plus a short "
                                        + "interpretation of the rows; when dataScope.restricted is true, "
                                        + "never claim rows outside the range do not exist.")
                        .tools(List.of("query_database"))
                        .build(),
                SubagentDeclaration.builder()
                        .name("report_writer")
                        .description("Turn query results into a short, structured written report.")
                        .inlineAgentsBody(
                                "You write clear, concise reports from provided data. Do not invent numbers; "
                                        + "base every statement on the supplied SQL results.")
                        .build());
    }
}
