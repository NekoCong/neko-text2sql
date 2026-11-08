package com.nekocong.text2sql.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.nl2sql.ExecutionListener;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.datasource.DatasourceAccessGuard;
import com.nekocong.text2sql.gbi.ChartInference;
import com.nekocong.text2sql.modelconfig.ModelResolver;
import com.nekocong.text2sql.nl2sql.ChartEvent;
import com.nekocong.text2sql.nl2sql.PromptAssembler;
import com.nekocong.text2sql.nl2sql.RowsEvent;
import com.nekocong.text2sql.nl2sql.SqlEvent;
import com.nekocong.text2sql.nl2sql.SqlGeneration;
import com.nekocong.text2sql.nl2sql.SqlGenerationService;
import com.nekocong.text2sql.nl2sql.SqlResultParser;
import com.nekocong.text2sql.nl2sql.StepEvent;
import com.nekocong.text2sql.nl2sql.Text2SqlEvent;
import com.nekocong.text2sql.nl2sql.Text2SqlResult;
import com.nekocong.text2sql.prompt.PromptCatalog;
import com.nekocong.text2sql.schema.FullSchemaProvider;
import com.nekocong.text2sql.security.AccessFixtures;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.TablePolicy;
import com.nekocong.text2sql.sql.DataScopeRewriter;
import com.nekocong.text2sql.sql.SqlExecutionException;
import com.nekocong.text2sql.sql.SqlExecutor;
import com.nekocong.text2sql.sql.SqlSafetyException;
import com.nekocong.text2sql.sql.SqlSafetyValidator;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Exercises the full deterministic pipeline (schema → generate → validate → permission rewrite →
 * execute → chart) with a stubbed model + resolver, so orchestration is verified without an LLM.
 */
@SpringBootTest
class Text2SqlPipelineServiceTest {

    /** A no-op model; the stubbed generation service ignores it. */
    private static final Model DUMMY_MODEL =
            new Model() {
                @Override
                public Flux<ChatResponse> stream(
                        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                    return Flux.empty();
                }

                @Override
                public String getModelName() {
                    return "test-dummy";
                }
            };

    @Autowired
    private DataSourceRegistry registry;

    @Autowired
    private FullSchemaProvider schemaProvider;

    @Autowired
    private SqlSafetyValidator safetyValidator;

    @Autowired
    private SqlExecutor sqlExecutor;

    private Text2SqlPipelineService pipelineReturning(String sql, String explanation) {
        SqlGenerationService stub =
                new SqlGenerationService(new PromptAssembler(new PromptCatalog()), new SqlResultParser(new ObjectMapper())) {
                    @Override
                    public Mono<SqlGeneration> generate(
                            Model model,
                            String dialect,
                            String schema,
                            String question,
                            String callerContext) {
                        return Mono.just(new SqlGeneration(sql, explanation));
                    }
                };
        ModelResolver resolver =
                new ModelResolver(null, null, null) {
                    @Override
                    public Mono<Model> resolve(String userId) {
                        return Mono.just(DUMMY_MODEL);
                    }
                };
        return new Text2SqlPipelineService(
                registry,
                schemaProvider,
                resolver,
                stub,
                safetyValidator,
                new DataScopeRewriter(new TablePolicy()),
                new DatasourceAccessGuard(registry),
                sqlExecutor,
                new ChartInference());
    }

    @Test
    void runsEndToEndAndExecutesTheEnforcedSql() {
        Text2SqlPipelineService pipeline =
                pipelineReturning("SELECT COUNT(*) AS student_count FROM student", "counts all students");

        Text2SqlResult result =
                pipeline.run(studentScope(), null, "how many students are there?").block();

        assertThat(result.datasourceId()).isEqualTo("campus");
        assertThat(result.sql()).containsIgnoringCase("student_no IN ('2023001')");
        assertThat(result.explanation()).contains("数据范围");
        assertThat(result.result().rowCount()).isEqualTo(1);
        assertThat(result.result().columns()).anyMatch(c -> c.name().equalsIgnoreCase("student_count"));
    }

    @Test
    void rejectsUnsafeGeneratedSqlBeforeExecution() {
        Text2SqlPipelineService pipeline = pipelineReturning("DROP TABLE student", null);

        assertThatThrownBy(() -> pipeline.run(studentScope(), null, "drop student").block())
                .isInstanceOf(SqlSafetyException.class);
    }

    @Test
    void surfacesExecutionErrors() {
        Text2SqlPipelineService pipeline = pipelineReturning("SELECT missing_column FROM student", null);

        assertThatThrownBy(() -> pipeline.run(studentScope(), null, "bad query").block())
                .isInstanceOf(SqlExecutionException.class);
    }

    @Test
    void streamEmitsStepsSqlRowsAndChartInOrder() {
        Text2SqlPipelineService pipeline =
                pipelineReturning("SELECT id FROM student ORDER BY id", "lists student ids");

        List<Text2SqlEvent> events =
                pipeline.stream(studentScope(), null, "list student ids").collectList().block();

        assertThat(events).isNotNull();
        assertThat(events.get(0)).isInstanceOf(StepEvent.class);
        assertThat(events)
                .anySatisfy(
                        event -> {
                            assertThat(event).isInstanceOf(SqlEvent.class);
                            assertThat(((SqlEvent) event).sql())
                                    .containsIgnoringCase("student_no IN ('2023001')");
                        });

        int rowsIndex = indexOf(events, RowsEvent.class);
        int chartIndex = indexOf(events, ChartEvent.class);
        assertThat(rowsIndex).isGreaterThanOrEqualTo(0);
        assertThat(chartIndex).isGreaterThan(rowsIndex);

        RowsEvent rows = (RowsEvent) events.get(rowsIndex);
        assertThat(rows.result().rowCount()).isEqualTo(1);
    }

    private static DataScope studentScope() {
        return AccessFixtures.student();
    }


    @Test
    void listenerReceivesTheEnforcedSqlAndRowCount() {
        Text2SqlPipelineService pipeline =
                pipelineReturning("SELECT COUNT(*) AS student_count FROM student", "counts");
        java.util.concurrent.atomic.AtomicReference<String> sql = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> datasource =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger rows = new java.util.concurrent.atomic.AtomicInteger();

        Text2SqlResult result =
                pipeline.run(
                        studentScope(),
                        "campus",
                        "how many students",
                        new ExecutionListener() {
                            @Override
                            public void onSuccess(String ds, String executedSql, int rowCount) {
                                datasource.set(ds);
                                sql.set(executedSql);
                                rows.set(rowCount);
                            }

                            @Override
                            public void onFailure(String ds, String message) {
                                // not expected
                            }
                        })
                        .block();

        assertThat(sql.get()).isEqualTo(result.sql());
        assertThat(sql.get()).containsIgnoringCase("student_no IN ('2023001')");
        assertThat(datasource.get()).isEqualTo("campus");
        assertThat(rows.get()).isEqualTo(1);
    }

    @Test
    void listenerReceivesTheFailureReason() {
        Text2SqlPipelineService pipeline = pipelineReturning("SELECT * FROM salary", null);
        java.util.concurrent.atomic.AtomicReference<String> failure =
                new java.util.concurrent.atomic.AtomicReference<>();

        assertThatThrownBy(
                        () ->
                                pipeline.run(
                                                studentScope(),
                                                "campus",
                                                "工资",
                                                new ExecutionListener() {
                                                    @Override
                                                    public void onSuccess(String ds, String sql, int rowCount) {
                                                        // not expected
                                                    }

                                                    @Override
                                                    public void onFailure(String ds, String message) {
                                                        failure.set(message);
                                                    }
                                                })
                                        .block())
                .isInstanceOf(com.nekocong.text2sql.security.SqlPermissionException.class);
        assertThat(failure.get()).contains("salary");
    }

    @Test
    void listenerIsNotifiedWhenTheDatasourceIsNotGranted() {
        Text2SqlPipelineService pipeline = pipelineReturning("SELECT 1", "one");
        DataScope noDatasource =
                new DataScope("u-student", "student", Set.of(1L), 1L, "2023001", Set.of());
        java.util.concurrent.atomic.AtomicReference<String> failure =
                new java.util.concurrent.atomic.AtomicReference<>();

        assertThatThrownBy(
                        () ->
                                pipeline.run(
                                                noDatasource,
                                                null,
                                                "q",
                                                new ExecutionListener() {
                                                    @Override
                                                    public void onSuccess(String ds, String sql, int rowCount) {
                                                        // not expected
                                                    }

                                                    @Override
                                                    public void onFailure(String ds, String message) {
                                                        failure.set(message);
                                                    }
                                                })
                                        .block())
                .isInstanceOf(com.nekocong.text2sql.security.SqlPermissionException.class);
        assertThat(failure.get()).contains("没有任何可用的数据源");
    }

    private static int indexOf(List<Text2SqlEvent> events, Class<? extends Text2SqlEvent> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
