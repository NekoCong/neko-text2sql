package com.nekocong.text2sql.service;

import com.nekocong.text2sql.access.DataScopePolicy;
import com.nekocong.text2sql.access.PrincipalAttribute;
import com.nekocong.text2sql.config.BlockingSchedulerConfig;
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
import com.nekocong.text2sql.security.DataScopeDescriber;
import com.nekocong.text2sql.sql.DataScopeRewriter;
import com.nekocong.text2sql.sql.SqlExecutor;
import com.nekocong.text2sql.sql.SqlSafetyValidator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
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

    /** Blocking work (schema introspection, retrieval) never runs on the event loop. */
    private Scheduler blockingScheduler = Schedulers.boundedElastic();

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
     * Injects the virtual-thread scheduler used for blocking schema work. Absent in plain unit
     * tests, where the bounded-elastic default is used instead.
     *
     * @param scheduler the blocking scheduler
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBlockingScheduler(
            @org.springframework.beans.factory.annotation.Qualifier(BlockingSchedulerConfig.BLOCKING_SCHEDULER)
                    Scheduler scheduler) {
        if (scheduler != null) {
            this.blockingScheduler = scheduler;
        }
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
        String safeSql = enforce(generation.sql(), id, scope);
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
        String safeSql = enforce(generation.sql(), id, scope);
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
     *
     * @param sql the generated SQL
     * @param datasourceId the already-resolved datasource the SQL will run against
     * @param scope the caller's data scope
     * @return the enforced SQL
     */
    private String enforce(String sql, String datasourceId, DataScope scope) {
        String safeSql = safetyValidator.validateAndNormalize(sql);
        String rewritten = rewriter.rewrite(safeSql, scope, datasourceId);
        // Defense in depth: the serialized rewrite must still be a single read-only statement.
        return safetyValidator.validateAndNormalize(rewritten);
    }

    /**
     * Appends a data-scope note so the answer states the restriction instead of silently returning a
     * narrower result (see the "越界问题的回答语义" requirement).
     *
     * <p>The label comes from the effective {@code FILTER} attributes, never the role name, so a
     * custom role is described by what its policies actually do.
     */
    static String scopedExplanation(String explanation, DataScope scope) {
        if (scope == null || scope.bypassesDataScope()) {
            return explanation;
        }
        String note = "结果已按当前账号的数据范围（" + scopeLabel(scope) + "）过滤";
        return (explanation == null || explanation.isBlank()) ? note : explanation + "（" + note + "）";
    }

    /** Labels the effective filter: personal rows, managed classes, or the generic policy wording. */
    private static String scopeLabel(DataScope scope) {
        Set<PrincipalAttribute> attributes =
                scope.access() == null
                        ? Set.of()
                        : scope.access().dataScopes().stream()
                                .filter(rule -> rule.policy() == DataScopePolicy.FILTER)
                                .map(rule -> PrincipalAttribute.fromCode(rule.principalAttribute()))
                                .flatMap(Optional::stream)
                                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (attributes.contains(PrincipalAttribute.STUDENT_NO)
                || attributes.contains(PrincipalAttribute.STUDENT_ID)) {
            return "仅本人";
        }
        if (attributes.contains(PrincipalAttribute.CLASS_IDS)) {
            return "所管班级";
        }
        return "当前数据范围";
    }

    /**
     * The caller block injected into the generation prompt, shared with the agent prompt so the two
     * paths describe a scope identically.
     */
    private static final DataScopeDescriber DESCRIBER = new DataScopeDescriber();

    /**
     * Describes the caller to the generation model, so first-person questions resolve to the
     * caller's own identifiers instead of being guessed from the question text.
     *
     * <p>The wording lives in {@link DataScopeDescriber#promptBlock(DataScope)}; this delegates so
     * the pipeline and the chat agent cannot drift apart.
     */
    private static String callerContext(DataScope scope) {
        return DESCRIBER.promptBlock(scope);
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
                    .subscribeOn(blockingScheduler)
                    .map(schema -> new SchemaRetrieval(schema, List.of(), null));
        }
        return Mono.fromCallable(() -> provider.retrieveForPrompt(id, scope, question))
                .subscribeOn(blockingScheduler);
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
