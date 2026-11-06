package com.nekocong.text2sql.service;

import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.datasource.DatasourceAccessGuard;
import com.nekocong.text2sql.gbi.ChartInference;
import com.nekocong.text2sql.knowledge.schema.CitationAwareSchemaProvider;
import com.nekocong.text2sql.knowledge.schema.SchemaRetrieval;
import com.nekocong.text2sql.modelconfig.ModelResolver;
import com.nekocong.text2sql.nl2sql.ChartEvent;
import com.nekocong.text2sql.nl2sql.CitationEvent;
import com.nekocong.text2sql.nl2sql.ExecutionListener;
import com.nekocong.text2sql.nl2sql.RowsEvent;
import com.nekocong.text2sql.nl2sql.SqlEvent;
import com.nekocong.text2sql.nl2sql.SqlGeneration;
import com.nekocong.text2sql.nl2sql.SqlGenerationService;
import com.nekocong.text2sql.nl2sql.StepEvent;
import com.nekocong.text2sql.nl2sql.Text2SqlEvent;
import com.nekocong.text2sql.nl2sql.Text2SqlResult;
import com.nekocong.text2sql.schema.SchemaProvider;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.sql.DataScopeRewriter;
import com.nekocong.text2sql.sql.SqlExecutor;
import com.nekocong.text2sql.sql.SqlSafetyValidator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The deterministic NL2SQL pipeline: schema grounding → SQL generation → safety validation → data
 * permission rewrite → read-only execution → chart inference.
 *
 * <p>This is the product path — reliable and cheap (one model call, one query). The chat agent sits
 * <em>on top</em> of it. The model is resolved per caller, the schema is trimmed to the caller's
 * scope, and the generated SQL is rewritten for the caller before execution. Emits {@link
 * Text2SqlEvent}s so the web layer can stream structured progress.
 */
@Service
public class Text2SqlPipelineService {

    private final DataSourceRegistry registry;
    private final SchemaProvider schemaProvider;
    private final ModelResolver modelResolver;
    private final SqlGenerationService generationService;
    private final SqlSafetyValidator safetyValidator;
    private final DataScopeRewriter rewriter;
    private final DatasourceAccessGuard datasourceGuard;
    private final SqlExecutor sqlExecutor;
    private final ChartInference chartInference;

    /** Optional: absent when the knowledge base is disabled (and in unit tests). */
    private ObjectProvider<CitationAwareSchemaProvider> citationProviders;

    /**
     * Creates the pipeline.
     *
     * @param registry the datasource registry
     * @param schemaProvider the schema grounding provider
     * @param modelResolver the per-user model resolver
     * @param generationService the model-backed SQL generator
     * @param safetyValidator the read-only SQL validator
     * @param rewriter the data-permission rewriter
     * @param sqlExecutor the read-only executor
     * @param chartInference the Generative-BI chart inference
     */
    public Text2SqlPipelineService(
            DataSourceRegistry registry,
            SchemaProvider schemaProvider,
            ModelResolver modelResolver,
            SqlGenerationService generationService,
            SqlSafetyValidator safetyValidator,
            DataScopeRewriter rewriter,
            DatasourceAccessGuard datasourceGuard,
            SqlExecutor sqlExecutor,
            ChartInference chartInference) {
        this.registry = registry;
        this.schemaProvider = schemaProvider;
        this.modelResolver = modelResolver;
        this.generationService = generationService;
        this.safetyValidator = safetyValidator;
        this.rewriter = rewriter;
        this.datasourceGuard = datasourceGuard;
        this.sqlExecutor = sqlExecutor;
        this.chartInference = chartInference;
    }

    /**
     * Injects the optional citation-aware schema provider without widening the constructor contract
     * every test constructs.
     *
     * @param providers the provider, resolved lazily so the knowledge base can be disabled
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCitationProviders(ObjectProvider<CitationAwareSchemaProvider> providers) {
        this.citationProviders = providers;
    }

    /**
     * Runs the pipeline and returns the complete result.
     *
     * @param scope the caller's data scope
     * @param datasourceId the datasource id (or {@code null} for the default)
     * @param question the natural-language question
     * @return a mono emitting the result
     */
    public Mono<Text2SqlResult> run(DataScope scope, String datasourceId, String question) {
        return run(scope, datasourceId, question, ExecutionListener.noop());
    }

    /**
     * Runs the pipeline and returns the complete result, notifying an execution listener.
     *
     * @param scope the caller's data scope
     * @param datasourceId the datasource id (or {@code null} for the default)
     * @param question the natural-language question
     * @param listener notified with the enforced SQL / failure reason
     * @return a mono emitting the result
     */
    public Mono<Text2SqlResult> run(
            DataScope scope, String datasourceId, String question, ExecutionListener listener) {
        return Mono.defer(
                () -> {
                    String id = resolveId(scope, datasourceId);
                    String dialect = dialectOf(id);
                    return Mono.zip(retrieval(id, scope, question), modelResolver.resolve(scope.userId()))
                            .flatMap(pair -> generationService.generate(
                                    pair.getT2(),
                                    dialect,
                                    pair.getT1().schema(),
                                    question,
                                    callerContext(scope)))
                            .flatMap(generation -> execute(id, dialect, generation, scope, listener));
                })
                .doOnError(error -> listener.onFailure(null, errorMessage(error)));
    }

    /**
     * Runs the pipeline and blocks for the result. Intended for non-web callers (agent tools,
     * tests) running on a bounded-elastic / tool-worker thread, never the event loop.
     *
     * @param scope the caller's data scope
     * @param datasourceId the datasource id (or {@code null} for the default)
     * @param question the natural-language question
     * @return the result
     */
    public Text2SqlResult runBlocking(DataScope scope, String datasourceId, String question) {
        return run(scope, datasourceId, question).block();
    }

    /**
     * Runs the pipeline and blocks, notifying an execution listener.
     *
     * @param scope the caller's data scope
     * @param datasourceId the datasource id (or {@code null} for the default)
     * @param question the natural-language question
     * @param listener notified with the enforced SQL / failure reason
     * @return the result
     */
    public Text2SqlResult runBlocking(
            DataScope scope, String datasourceId, String question, ExecutionListener listener) {
        return run(scope, datasourceId, question, listener).block();
    }

    /**
     * Runs the pipeline as a stream of progress + result events.
     *
     * @param scope the caller's data scope
     * @param datasourceId the datasource id (or {@code null} for the default)
     * @param question the natural-language question
     * @return a flux of pipeline events
     */
    public Flux<Text2SqlEvent> stream(DataScope scope, String datasourceId, String question) {
        return stream(scope, datasourceId, question, ExecutionListener.noop());
    }

    /**
     * Runs the pipeline as a stream, notifying an execution listener with the enforced SQL.
     *
     * @param scope the caller's data scope
     * @param datasourceId the datasource id (or {@code null} for the default)
     * @param question the natural-language question
     * @param listener notified with the enforced SQL / failure reason
     * @return a flux of pipeline events
     */
    public Flux<Text2SqlEvent> stream(
            DataScope scope, String datasourceId, String question, ExecutionListener listener) {
        return Flux.defer(
                () -> {
                    String id = resolveId(scope, datasourceId);
                    String dialect = dialectOf(id);
                    return Flux.concat(
                                    Flux.just(new StepEvent("introspect", "读取数据源表结构")),
                                    Mono.zip(retrieval(id, scope, question), modelResolver.resolve(scope.userId()))
                                            .flatMapMany(
                                                    pair ->
                                                            Flux.concat(
                                                                    citationEvents(pair.getT1()),
                                                                    Flux.just(new StepEvent("generate", "调用模型生成 SQL")),
                                                                    generationService
                                                                            .generate(
                                                                                    pair.getT2(),
                                                                                    dialect,
                                                                                    pair.getT1().schema(),
                                                                                    question,
                                                                                    callerContext(scope))
                                                                            .flatMapMany(
                                                                                    gen -> toEvents(id, dialect, gen, scope, listener)))));
                })
                .doOnError(error -> listener.onFailure(null, errorMessage(error)));
    }

    private Flux<Text2SqlEvent> toEvents(
            String id,
            String dialect,
            SqlGeneration generation,
            DataScope scope,
            ExecutionListener listener) {
        String safeSql = enforce(generation.sql(), scope);
        return Flux.concat(
                Flux.just(new SqlEvent(id, dialect, safeSql, scopedExplanation(generation.explanation(), scope))),
                Flux.just(new StepEvent("execute", "执行只读查询")),
                sqlExecutor
                        .execute(id, safeSql)
                        .doOnNext(rows -> listener.onSuccess(id, safeSql, rows.rowCount()))
                        .flatMapMany(
                                rows ->
                                        Flux.just(
                                                new RowsEvent(rows), new ChartEvent(chartInference.infer(rows)))));
    }

    private Mono<Text2SqlResult> execute(
            String id,
            String dialect,
            SqlGeneration generation,
            DataScope scope,
            ExecutionListener listener) {
        String safeSql = enforce(generation.sql(), scope);
        return sqlExecutor
                .execute(id, safeSql)
                .doOnNext(rows -> listener.onSuccess(id, safeSql, rows.rowCount()))
                .map(
                        result ->
                                new Text2SqlResult(
                                        id,
                                        dialect,
                                        safeSql,
                                        scopedExplanation(generation.explanation(), scope),
                                        result,
                                        chartInference.infer(result)));
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return (message == null || message.isBlank()) ? error.getClass().getSimpleName() : message;
    }

    /**
     * Validates then rewrites: the statement that is shown to the user and executed is the enforced
     * one, never the raw model output.
     */
    private String enforce(String sql, DataScope scope) {
        String safeSql = safetyValidator.validateAndNormalize(sql);
        String rewritten = rewriter.rewrite(safeSql, scope);
        // Defense in depth: the serialized rewrite must still be a single read-only statement.
        return safetyValidator.validateAndNormalize(rewritten);
    }

    /**
     * Appends a data-scope note so the answer states the restriction instead of silently returning a
     * narrower result (see the "越界问题的回答语义" requirement).
     */
    private static String scopedExplanation(String explanation, DataScope scope) {
        if (scope.isAdmin()) {
            return explanation;
        }
        String scopeText = scope.isStudent() ? "仅本人" : "所管班级";
        String note = "结果已按当前账号的数据范围（" + scopeText + "）过滤";
        return (explanation == null || explanation.isBlank()) ? note : explanation + "（" + note + "）";
    }

    /**
     * Describes the caller to the generation model, so first-person questions resolve to the
     * caller's own identifiers instead of being guessed from the question text.
     */
    private static String callerContext(DataScope scope) {
        if (scope.isAdmin()) {
            return "管理员：可见全部数据，不需要按调用者过滤。";
        }
        if (scope.isStudent()) {
            return "学生：本人学号 "
                    + scope.studentNo()
                    + "，所属班级 id "
                    + scope.classIds().stream().sorted().toList()
                    + "。问题中的“我/我的”指该学生本人，用 student_no = '"
                    + scope.studentNo()
                    + "' 过滤。";
        }
        return "辅导员：管理班级 id "
                + scope.classIds().stream().sorted().toList()
                + "。问题中的“我管理的班/本班”指这些班级，用 class_id IN (...) 过滤。";
    }

    /**
     * Retrieves the schema together with its citation evidence. When the knowledge base is disabled
     * there is no citation-aware provider, so the plain {@link SchemaProvider} is used and the
     * citation list stays empty.
     */
    private Mono<SchemaRetrieval> retrieval(String id, DataScope scope, String question) {
        CitationAwareSchemaProvider provider =
                citationProviders == null ? null : citationProviders.getIfAvailable();
        if (provider == null) {
            return Mono.fromCallable(() -> schemaProvider.renderForPrompt(id, scope, question))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(schema -> new SchemaRetrieval(schema, List.of(), null));
        }
        return Mono.fromCallable(() -> provider.retrieveForPrompt(id, scope, question))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Flux<Text2SqlEvent> citationEvents(SchemaRetrieval retrieval) {
        if (retrieval == null || retrieval.citations() == null || retrieval.citations().isEmpty()) {
            return Flux.empty();
        }
        return Flux.just(new CitationEvent(retrieval.citations()));
    }

    private String resolveId(DataScope scope, String datasourceId) {
        return datasourceGuard.resolve(scope, datasourceId);
    }

    private String dialectOf(String id) {
        return registry.definition(id).resolvedDialect().name();
    }
}
