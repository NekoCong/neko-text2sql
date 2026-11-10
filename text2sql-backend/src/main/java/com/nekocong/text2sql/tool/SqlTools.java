package com.nekocong.text2sql.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.datasource.DatasourceAccessGuard;
import com.nekocong.text2sql.nl2sql.ExecutionListener;
import com.nekocong.text2sql.nl2sql.Text2SqlResult;
import com.nekocong.text2sql.schema.SchemaIntrospector;
import com.nekocong.text2sql.schema.SchemaRenderer;
import com.nekocong.text2sql.schema.model.TableSchema;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.DataScopeDescriber;
import com.nekocong.text2sql.security.TablePolicy;
import com.nekocong.text2sql.service.Text2SqlPipelineService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * AgentScope tools that expose the NL2SQL pipeline to the chat agent.
 *
 * <p>Deliberately high-level: the agent is given one authoritative {@code query_database} capability
 * (which internally does schema grounding → generation → validation → permission rewrite →
 * execution) plus light read-only exploration helpers, rather than the raw list/describe/execute
 * ladder that invites multi-hop, failure-prone loops.
 *
 * <p>Every method takes a {@link DataScope} parameter that is <em>not</em> annotated with {@code
 * @ToolParam}: AgentScope treats such parameters as context and injects them from the runtime
 * context, so the model can never supply or influence the caller's identity.
 *
 * <p>All tools run on AgentScope's tool-worker threads, so the blocking pipeline call is safe here.
 */
@Component
public class SqlTools {

    private final DataSourceRegistry registry;
    private final SchemaIntrospector introspector;
    private final SchemaRenderer renderer;
    private final Text2SqlPipelineService pipeline;
    private final TablePolicy policy;
    private final DatasourceAccessGuard datasourceGuard;
    private final ObjectMapper objectMapper;
    private final DataScopeDescriber describer = new DataScopeDescriber();

    /**
     * Creates the tools.
     *
     * @param registry the datasource registry
     * @param introspector the schema introspector
     * @param renderer the schema DDL renderer
     * @param pipeline the NL2SQL pipeline
     * @param policy the table policy used to trim metadata
     * @param datasourceGuard the datasource whitelist guard
     * @param objectMapper the shared Jackson mapper
     */
    public SqlTools(
            DataSourceRegistry registry,
            SchemaIntrospector introspector,
            SchemaRenderer renderer,
            Text2SqlPipelineService pipeline,
            TablePolicy policy,
            DatasourceAccessGuard datasourceGuard,
            ObjectMapper objectMapper) {
        this.registry = registry;
        this.introspector = introspector;
        this.renderer = renderer;
        this.pipeline = pipeline;
        this.policy = policy;
        this.datasourceGuard = datasourceGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists the tables of a datasource, restricted to the caller's scope.
     *
     * @param datasourceId datasource id (omit for the default)
     * @param scope the caller's data scope (injected, never model-supplied)
     * @return a comma-separated table list
     */
    @Tool(
            name = "list_tables",
            description = "List the tables available in a datasource.",
            readOnly = true,
            concurrencySafe = true)
    public String listTables(
            @ToolParam(
                            name = "datasource_id",
                            description = "Datasource id; omit for the default datasource",
                            required = false)
                    String datasourceId,
            DataScope scope) {
        return describeTables(datasourceId, scope).stream()
                .map(TableSchema::name)
                .collect(Collectors.joining(", "));
    }

    /**
     * Shows the DDL of a single table, if it is inside the caller's scope.
     *
     * @param table the table name
     * @param datasourceId datasource id (omit for the default)
     * @param scope the caller's data scope (injected, never model-supplied)
     * @return the table DDL, or an error message listing available tables
     */
    @Tool(
            name = "describe_table",
            description = "Show the columns and foreign keys (DDL) of one table in a datasource.",
            readOnly = true,
            concurrencySafe = true)
    public String describeTable(
            @ToolParam(name = "table", description = "Table name") String table,
            @ToolParam(
                            name = "datasource_id",
                            description = "Datasource id; omit for the default datasource",
                            required = false)
                    String datasourceId,
            DataScope scope) {
        List<TableSchema> tables = describeTables(datasourceId, scope);
        return tables.stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(table))
                .findFirst()
                .map(renderer::renderTable)
                .orElseGet(
                        () ->
                                "Unknown table '"
                                        + table
                                        + "'. Available tables: "
                                        + tables.stream().map(TableSchema::name).collect(Collectors.joining(", ")));
    }

    /**
     * Answers a data question by generating and running a read-only SQL query under the caller's
     * scope.
     *
     * @param question the natural-language data question
     * @param datasourceId datasource id (omit for the default)
     * @param scope the caller's data scope (injected, never model-supplied)
     * @return the SQL and result rows as JSON
     */
    @Tool(
            name = "query_database",
            description =
                    "Answer a natural-language data question by generating and executing a read-only "
                            + "SQL query. Returns JSON {\"result\": {sql, result, chart, ...}, "
                            + "\"dataScope\": {restricted, role, summary}}: read the SQL and rows from "
                            + "result and the range from dataScope. Prefer this over writing SQL yourself.",
            readOnly = true,
            concurrencySafe = false)
    public String queryDatabase(
            @ToolParam(name = "question", description = "The natural-language data question")
                    String question,
            @ToolParam(
                            name = "datasource_id",
                            description = "Datasource id; omit for the default datasource",
                            required = false)
                    String datasourceId,
            DataScope scope,
            ExecutionListener listener) {
        Text2SqlResult result = pipeline.runBlocking(scope, datasourceId, question, listener);
        try {
            return objectMapper.writeValueAsString(ScopedQueryResult.of(result, scope, describer));
        } catch (JsonProcessingException ex) {
            return "{\"result\":{\"sql\":\""
                    + result.sql().replace("\"", "'")
                    + "\",\"error\":\"failed to serialize result\"}}";
        }
    }

    private List<TableSchema> describeTables(String datasourceId, DataScope scope) {
        String resolved = datasourceGuard.resolve(scope, datasourceId);
        return introspector.introspect(registry.resolve(resolved)).stream()
                .filter(table -> policy.allows(scope, resolved, table.name()))
                .toList();
    }
}
