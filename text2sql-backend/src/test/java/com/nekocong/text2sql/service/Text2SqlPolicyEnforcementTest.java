package com.nekocong.text2sql.service;

import static com.nekocong.text2sql.security.AccessFixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.DataScopePolicy;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.datasource.DatasourceAccessGuard;
import com.nekocong.text2sql.gbi.ChartInference;
import com.nekocong.text2sql.modelconfig.ModelResolver;
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
import com.nekocong.text2sql.security.SqlPermissionException;
import com.nekocong.text2sql.security.TablePolicy;
import com.nekocong.text2sql.sql.DataScopeRewriter;
import com.nekocong.text2sql.sql.SqlExecutor;
import com.nekocong.text2sql.sql.SqlSafetyValidator;
import com.nekocong.text2sql.sql.model.QueryResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The pipeline under policy-driven enforcement: a denied or unscoped request fails before any SQL
 * reaches the executor, and a custom role is filtered from its policy alone.
 *
 * <p>Uses a stubbed generator and a counting executor, so no generated query is actually run.
 */
@SpringBootTest
class Text2SqlPolicyEnforcementTest {

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
    private SqlExecutor realExecutor;
    /** Delegates to the real executor and counts calls, so "nothing ran" is provable. */
    private static final class CountingExecutor extends SqlExecutor {
        private final SqlExecutor delegate;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingExecutor(SqlExecutor delegate) {
            super(null, null);
            this.delegate = delegate;
        }

        @Override
        public Mono<QueryResult> execute(String datasourceId, String sql) {
            calls.incrementAndGet();
            return delegate.execute(datasourceId, sql);
        }

        @Override
        public QueryResult executeBlocking(String datasourceId, String sql) {
            calls.incrementAndGet();
            return delegate.executeBlocking(datasourceId, sql);
        }
    }

    private Text2SqlPipelineService pipelineReturning(
            String sql, SqlExecutor executor, AtomicReference<String> capturedContext) {
        SqlGenerationService stub =
                new SqlGenerationService(new PromptAssembler(new PromptCatalog()), new SqlResultParser(new ObjectMapper())) {
                    @Override
                    public Mono<SqlGeneration> generate(
                            Model model,
                            String dialect,
                            String schema,
                            String question,
                            String callerContext) {
                        if (capturedContext != null) {
                            capturedContext.set(callerContext);
                        }
                        return Mono.just(new SqlGeneration(sql, "原始说明"));
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
                executor,
                new ChartInference());
    }

    @Test
    void aDeniedTableIsRejectedWithoutExecutingAnySql() {
        CountingExecutor executor = new CountingExecutor(realExecutor);
        DataScope denied = AccessFixtures.custom(
                "reviewer",
                List.of(rule("d1", "campus", "student", DataScopePolicy.DENY, null, null)),
                Set.of(1L));
        Text2SqlPipelineService pipeline =
                pipelineReturning("SELECT student_no FROM student", executor, null);

        assertThatThrownBy(() -> pipeline.runBlocking(denied, "campus", "列出学号"))
                .isInstanceOf(SqlPermissionException.class);

        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void aScopeWithoutResolvedAccessIsRejectedWithoutExecutingAnySql() {
        CountingExecutor executor = new CountingExecutor(realExecutor);
        DataScope legacy =
                new DataScope("u", "student", Set.of(1L), 1L, "2023001", Set.of("campus"));
        Text2SqlPipelineService pipeline =
                pipelineReturning("SELECT student_no FROM student", executor, null);

        assertThatThrownBy(() -> pipeline.runBlocking(legacy, "campus", "列出学号"))
                .isInstanceOf(SqlPermissionException.class);

        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void aCustomRoleIsFilteredFromItsPolicyAndSaysSo() {
        CountingExecutor executor = new CountingExecutor(realExecutor);
        DataScope teacher = AccessFixtures.custom(
                "teacher",
                List.of(rule("t1", "campus", "score", DataScopePolicy.FILTER, "class_id", "classIds")),
                Set.of(1L, 3L));
        Text2SqlPipelineService pipeline =
                pipelineReturning("SELECT class_id FROM score", executor, null);

        Text2SqlResult result = pipeline.runBlocking(teacher, "campus", "平均分");
        assertThat(executor.calls.get()).isEqualTo(1);
        assertThat(result.sql()).containsIgnoringCase("class_id IN (1, 3)");
        assertThat(result.explanation()).contains("结果已按当前账号的数据范围");
    }

    @Test
    void theCallerContextDescribesTheEffectiveRoleNotTheLegacyName() {
        AtomicReference<String> context = new AtomicReference<>();
        DataScope teacher = AccessFixtures.custom(
                "teacher",
                List.of(rule("t1", "campus", "score", DataScopePolicy.FILTER, "class_id", "classIds")),
                Set.of(1L));
        pipelineReturning("SELECT COUNT(*) AS n FROM score", new CountingExecutor(realExecutor), context)
                .runBlocking(teacher, "campus", "人数");

        assertThat(context.get()).contains("teacher").contains("管理班级 id [1]");
    }

    @Test
    void aBypassScopeGetsNoScopeNote() {
        DataScope admin = AccessFixtures.admin();

        Text2SqlResult result = pipelineReturning(
                        "SELECT COUNT(*) AS n FROM score", new CountingExecutor(realExecutor), null)
                .runBlocking(admin, "campus", "人数");

        assertThat(result.explanation()).isEqualTo("原始说明");
    }

    @Test
    void theStreamEmitsTheEnforcedSqlForACustomRole() {
        DataScope attendanceReader = AccessFixtures.custom(
                "teacher",
                List.of(rule("t2", "campus", "attendance", DataScopePolicy.FILTER, "class_id", "classIds")),
                Set.of(2L));

        List<Text2SqlEvent> events = pipelineReturning(
                        "SELECT class_id FROM attendance", new CountingExecutor(realExecutor), null)
                .stream(attendanceReader, "campus", "考勤")
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0)).isInstanceOf(StepEvent.class);
        assertThat(events).anySatisfy(event -> {
            if (event instanceof SqlEvent sql) {
                assertThat(sql.sql()).containsIgnoringCase("class_id IN (2)");
            }
        });
        assertThat(events).anyMatch(RowsEvent.class::isInstance);
    }
}
