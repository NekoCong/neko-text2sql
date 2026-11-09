package com.nekocong.text2sql.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.api.dto.DataScopeRequest;
import com.nekocong.text2sql.access.api.dto.DataScopeView;
import com.nekocong.text2sql.config.BlockingSchedulerConfig;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.schema.SchemaIntrospector;
import com.nekocong.text2sql.schema.model.ColumnSchema;
import com.nekocong.text2sql.schema.model.TableSchema;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * The data-scope admin API: list, upsert and delete the per-role, per-datasource, per-table
 * policies ({@code ALL} / {@code FILTER} / {@code DENY}).
 *
 * <p>Kept separate from {@link AccessAdminService} (roles) and {@link RolePermissionService}
 * (permission matrix) so each stays focused. The transaction and audit patterns are the same:
 *
 * <ul>
 *   <li>An upsert or delete runs in one reactive transaction: the row change and its {@code
 *       data_scope.upsert} / {@code data_scope.delete} {@code SUCCESS} audit row commit together or
 *       not at all, so a failed audit insert also rolls the change back.
 *   <li>A rejection decided against the request or loaded data (a non-identifier name, unknown
 *       role, unknown datasource, unknown table or column, unknown attribute, a type mismatch) writes
 *       exactly one {@code DENIED} row in its own {@code REQUIRES_NEW} transaction, then fails with the
 *       mapped status ({@code 404} / {@code 422}).
 *   <li>Only a structurally malformed request (a missing body, missing fields, a bad policy, a {@code
 *       FILTER} without a column/attribute, an {@code ALL}/{@code DENY} carrying one) answers {@code
 *       400} without auditing: there is no identifiable change to attribute the attempt to.
 * </ul>
 *
 * <p>Fail closed, and never trust the body as SQL: the only names that can reach a later predicate are
 * a table and column validated (and canonicalised) against the datasource's <em>live</em> metadata and
 * an attribute validated against the closed {@link PrincipalAttribute} catalog. The live metadata is
 * blocking JDBC ({@link SchemaIntrospector#introspect}), so it is read on the {@code blockingScheduler},
 * never on the event loop — and validated <em>before</em> the mutation transaction opens, so no
 * application-database connection is held across that work.
 */
@Service
public class DataScopeService {

    private static final Logger log = LoggerFactory.getLogger(DataScopeService.class);

    /** A bare, unqualified SQL identifier: what a table or column name may be. */
    static final String IDENTIFIER_PATTERN_DESCRIPTION = "[A-Za-z_][A-Za-z0-9_]*";

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^" + IDENTIFIER_PATTERN_DESCRIPTION + "$");

    /**
     * The datasource id space. Deliberately wider than a SQL identifier and narrower than "anything":
     * ids come from configuration or the management API and may contain dots, dashes and slashes
     * (e.g. {@code campus}, {@code erp-prod}, {@code tenant.a}), and they are only ever bound as
     * parameters, never interpolated. {@link DataSourceDefinition#setId} does not constrain the form.
     */
    private static final Pattern DATASOURCE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /** The alphabet app-generated ids use (rule ids, role ids, UUIDs and the seed ids). */
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** SQL type names that hold text. */
    private static final Set<String> TEXT_TYPES =
            Set.of("CHAR", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "NVARCHAR2", "TEXT", "CLOB",
                    "MEDIUMTEXT", "LONGTEXT", "TINYTEXT", "STRING");

    /** Matches the multi-valued {@code classIds} attribute (rendered as an {@code IN} list). */
    private static final PrincipalAttribute MULTIVALUED_ATTRIBUTE = PrincipalAttribute.CLASS_IDS;

    /** Matches {@code access_audit.reason VARCHAR(512)}. */
    private static final int MAX_REASON_LENGTH = 512;

    /** How much of an echoed name to include in a rejection message. */
    private static final int MAX_ECHOED_LENGTH = 64;

    private static final String ENTITY_TYPE = "DATA_SCOPE";
    private static final String ACTION_UPSERT = "data_scope.upsert";
    private static final String ACTION_DELETE = "data_scope.delete";

    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_DENIED = "DENIED";

    private static final String REASON_BODY_MISSING = "请求体不能为空";
    private static final String REASON_ROLE_MISSING = "角色不存在";
    private static final String REASON_DATASOURCE_MISSING = "数据源不存在";
    private static final String REASON_SCOPE_MISSING = "数据范围不存在";
    private static final String REASON_POLICY_INVALID = "策略类型无效";
    private static final String REASON_FILTER_INCOMPLETE = "FILTER 策略必须指定过滤列和主体属性";
    private static final String REASON_NON_FILTER_HAS_COLUMN = "ALL/DENY 策略不能指定过滤列或主体属性";
    private static final String REASON_IDENTIFIER_INVALID = "标识符不合法";
    private static final String REASON_TABLE_MISSING = "表不存在：";
    private static final String REASON_COLUMN_MISSING = "列不存在：";
    private static final String REASON_UNKNOWN_ATTRIBUTE = "未知主体属性：";
    private static final String REASON_TYPE_MISMATCH = "列类型与主体属性不匹配：";

    private final AccessAdminRepository repository;
    private final TransactionalOperator transactions;
    private final TransactionalOperator newTransactions;
    private final ObjectMapper objectMapper;
    private final DataSourceRegistry datasources;
    private final SchemaIntrospector introspector;
    private final Scheduler blockingScheduler;

    /**
     * Creates the service.
     *
     * @param repository the admin repository
     * @param transactionManager the R2DBC reactive transaction manager
     * @param objectMapper the shared Jackson mapper, used for the audit JSON payloads
     * @param datasources the target-datasource registry, to validate and resolve the datasource
     * @param introspector the live metadata reader (blocking JDBC)
     * @param blockingScheduler the scheduler blocking metadata reads are offloaded to
     */
    public DataScopeService(
            AccessAdminRepository repository,
            ReactiveTransactionManager transactionManager,
            ObjectMapper objectMapper,
            DataSourceRegistry datasources,
            SchemaIntrospector introspector,
            @Qualifier(BlockingSchedulerConfig.BLOCKING_SCHEDULER) Scheduler blockingScheduler) {
        this.repository = repository;
        this.transactions = TransactionalOperator.create(transactionManager);
        this.newTransactions =
                TransactionalOperator.create(
                        transactionManager,
                        new DefaultTransactionDefinition(
                                TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        this.objectMapper = objectMapper;
        this.datasources = datasources;
        this.introspector = introspector;
        this.blockingScheduler = blockingScheduler;
    }

    /**
     * Lists one role's data-scope rules, ordered by datasource then table.
     *
     * <p>An unknown role is a {@code 404}; reads are not audited. A stored rule whose policy cannot be
     * parsed is mapped to {@code DENY} by the repository (fail closed).
     *
     * @param roleId the role id
     * @param datasourceId the datasource to filter by, or {@code null}/blank for all
     * @return the rules
     */
    public Mono<List<DataScopeView>> list(String roleId, String datasourceId) {
        return Mono.defer(
                () -> {
                    requireId(roleId, "角色标识无效");
                    String datasource = blankToNull(datasourceId);
                    if (datasource != null) {
                        requireId(datasource, "数据源标识无效");
                    }
                    return repository
                            .role(roleId)
                            .switchIfEmpty(Mono.error(notFound(REASON_ROLE_MISSING)))
                            .then(Mono.defer(() -> repository.dataScopesOf(roleId, datasource)))
                            .map(rules -> rules.stream().map(DataScopeView::of).toList());
                });
    }

    /**
     * Inserts or updates the rule for ({@code roleId}, {@code datasourceId}, {@code tableName}).
     *
     * <p>An existing rule keeps its id and creation time; only the policy, column and attribute
     * change. The table and column are stored under their canonical (introspected) spelling, so a
     * case variant of the same name updates the same row rather than creating a second one.
     *
     * <p>Validation runs first, outside any transaction: the role, datasource, attribute, table,
     * column and type are all checked before the mutation transaction opens, so a JDBC metadata read
     * never holds an application-database connection.
     *
     * @param actorId the acting user id
     * @param request the rule
     * @return the stored rule
     */
    public Mono<DataScopeView> upsert(String actorId, DataScopeRequest request) {
        return Mono.defer(
                        () -> {
                            requireShape(request);
                            DataScopeRule candidate = candidate(request);
                            return requireRole(actorId, candidate)
                                    .then(Mono.defer(() -> requireDatasource(actorId, candidate)))
                                    .then(
                                            Mono.defer(
                                                    () ->
                                                            requireTableAndColumnAndType(
                                                                    actorId, request, candidate)))
                                    .flatMap(rule -> store(actorId, rule));
                        })
                .onErrorMap(Denial.class, Denial::toException)
                .map(DataScopeView::of);
    }

    /**
     * Deletes one rule.
     *
     * <p>An unknown id is a {@code 404}, audited as {@code DENIED}: the attempt is attributable to
     * the id.
     *
     * @param actorId the acting user id
     * @param id the rule id
     * @return completion
     */
    public Mono<Void> delete(String actorId, String id) {
        return Mono.defer(
                        () -> {
                            requireId(id, "数据范围标识无效");
                            return repository
                                    .dataScopeById(id)
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () ->
                                                            deny(
                                                                    actorId,
                                                                    ACTION_DELETE,
                                                                    id,
                                                                    null,
                                                                    null,
                                                                    missing())))
                                    // Only the mutation and its audit run in the transaction.
                                    .flatMap(
                                            before ->
                                                    Mono.defer(
                                                                    () ->
                                                                            deleteAndAudit(
                                                                                    actorId,
                                                                                    id,
                                                                                    before))
                                                            .as(transactions::transactional))
                                    .then();
                        })
                .onErrorMap(Denial.class, Denial::toException);
    }

    // --- shape validation (400, not audited) ------------------------------------------------

    /**
     * Applies the checks that reject a structurally malformed request before anything is loaded and
     * without auditing: there is no identifiable change to attribute the attempt to.
     *
     * <p>The table and column <em>syntax</em> is not checked here: a name that fails the bare-identifier
     * rule (raw SQL, a qualified or quoted name, an expression) is rejected with {@code 422} and audited
     * by {@link #checkIdentifiers}, matching the "reject anything else (422)" contract for identifier
     * input.
     *
     * @param request the body
     */
    private static void requireShape(DataScopeRequest request) {
        if (request == null) {
            throw badRequest(REASON_BODY_MISSING);
        }
        if (blankToNull(request.roleId()) == null) {
            throw badRequest("角色标识不能为空");
        }
        if (blankToNull(request.datasourceId()) == null) {
            throw badRequest("数据源标识不能为空");
        }
        if (blankToNull(request.tableName()) == null) {
            throw badRequest("表标识不能为空");
        }
        DataScopePolicy policy = parsePolicy(request.policy());
        String columnName = blankToNull(request.columnName());
        String attribute = blankToNull(request.principalAttribute());
        if (policy == DataScopePolicy.FILTER) {
            if (columnName == null || attribute == null) {
                throw badRequest(REASON_FILTER_INCOMPLETE);
            }
        } else if (columnName != null || attribute != null) {
            // ALL/DENY carry no predicate: a stray column must not be silently dropped, or a client
            // could believe it configured a filter that is not stored.
            throw badRequest(REASON_NON_FILTER_HAS_COLUMN);
        }
        requireId(request.roleId(), "角色标识无效");
        requireDatasourceId(request.datasourceId());
    }

    /** Builds the requested rule (no id, names not yet canonical) from a shape-valid body. */
    private static DataScopeRule candidate(DataScopeRequest request) {
        return new DataScopeRule(
                null,
                blankToNull(request.roleId()),
                blankToNull(request.datasourceId()),
                blankToNull(request.tableName()),
                parsePolicy(request.policy()),
                blankToNull(request.columnName()),
                blankToNull(request.principalAttribute()));
    }

    // --- referenced-data validation (audited denials) ---------------------------------------

    /**
     * Requires the role to exist. An unknown role is audited as {@code DENIED} with the requested
     * rule as the after state.
     */
    private Mono<Void> requireRole(String actorId, DataScopeRule requested) {
        return repository
                .role(requested.roleId())
                .map(role -> Boolean.TRUE)
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(
                        exists ->
                                exists
                                        ? Mono.<Void>empty()
                                        : deny(
                                                actorId,
                                                ACTION_UPSERT,
                                                null,
                                                null,
                                                requested,
                                                new Denial(
                                                        REASON_ROLE_MISSING,
                                                        HttpStatus.NOT_FOUND))
                                                .then());
    }

    /**
     * Requires the datasource to exist in the registry, and returns the resolved pool, so later
     * metadata work reuses it instead of resolving again (a registry removal in between would
     * otherwise turn a {@code 404} into a server error).
     *
     * <p>Resolution is offloaded to the blocking scheduler: {@code resolve} is {@code synchronized}
     * over the pool map, so it can wait behind a concurrent registration.
     */
    private Mono<DataSource> requireDatasource(String actorId, DataScopeRule requested) {
        return Mono.fromCallable(() -> datasources.resolve(requested.datasourceId()))
                .subscribeOn(blockingScheduler)
                .<Optional<DataSource>>map(Optional::of)
                .onErrorResume(
                        NoSuchElementException.class,
                        error ->
                                deny(
                                                actorId,
                                                ACTION_UPSERT,
                                                null,
                                                null,
                                                requested,
                                                new Denial(
                                                        REASON_DATASOURCE_MISSING,
                                                        HttpStatus.NOT_FOUND))
                                        .then(Mono.just(Optional.<DataSource>empty())))
                .<DataSource>flatMap(
                        resolved ->
                                resolved.map(Mono::just)
                                        .orElseGet(Mono::empty));
    }

    /**
     * Validates the name syntax, the table, the column, the attribute and the attribute/column type
     * compatibility against the live metadata, and returns the rule with canonical names.
     *
     * <p>One introspection pass answers every metadata check, because it is the expensive part.
     * Introspection is blocking JDBC, so it runs on the blocking scheduler; an introspection failure
     * propagates (fail closed) rather than storing a policy against unverified metadata.
     *
     * @param actorId the acting user id
     * @param request the body (for the raw table/column/attribute spellings)
     * @param candidate the candidate rule
     * @return the rule ready to store, with canonical table and column names
     */
    private Mono<DataScopeRule> requireTableAndColumnAndType(
            String actorId, DataScopeRequest request, DataScopeRule candidate) {
        return Mono.fromCallable(
                        () -> {
                            Optional<Denial> syntax = checkIdentifiers(request);
                            if (syntax.isPresent()) {
                                return Inspection.reject(syntax.get());
                            }
                            return inspect(resolveDatasource(candidate.datasourceId()), candidate);
                        })
                .subscribeOn(blockingScheduler)
                .flatMap(
                        inspection ->
                                inspection.rejected()
                                        ? deny(
                                                        actorId,
                                                        ACTION_UPSERT,
                                                        null,
                                                        null,
                                                        candidate,
                                                        inspection.denial())
                                                .then(Mono.<DataScopeRule>empty())
                                        : Mono.just(inspection.rule()));
    }

    /**
     * The result of one validation pass: either the canonical rule to store, or the rejection.
     *
     * @param rule the canonical rule, or {@code null} when rejected
     * @param denial the rejection, or {@code null} when valid
     */
    private record Inspection(DataScopeRule rule, Denial denial) {

        private static Inspection of(DataScopeRule rule) {
            return new Inspection(rule, null);
        }

        private static Inspection reject(Denial denial) {
            return new Inspection(null, denial);
        }

        private boolean rejected() {
            return denial != null;
        }
    }

    /**
     * Rejects a table, column or attribute value that is not a bare
     * {@code [A-Za-z_][A-Za-z0-9_]*} identifier (or, for the attribute, a catalog code).
     *
     * @param request the body
     * @return the rejection, or empty when every name is acceptable
     */
    private static Optional<Denial> checkIdentifiers(DataScopeRequest request) {
        if (!isIdentifier(request.tableName())) {
            return Optional.of(
                    new Denial(
                            REASON_IDENTIFIER_INVALID + "：" + echo(request.tableName()),
                            HttpStatus.UNPROCESSABLE_ENTITY));
        }
        String columnName = blankToNull(request.columnName());
        if (columnName != null && !isIdentifier(columnName)) {
            return Optional.of(
                    new Denial(
                            REASON_IDENTIFIER_INVALID + "：" + echo(columnName),
                            HttpStatus.UNPROCESSABLE_ENTITY));
        }
        String attribute = blankToNull(request.principalAttribute());
        Optional<PrincipalAttribute> resolved = PrincipalAttribute.fromCode(attribute);
        if (attribute != null && resolved.isEmpty()) {
            return Optional.of(
                    new Denial(
                            REASON_UNKNOWN_ATTRIBUTE + echo(attribute),
                            HttpStatus.UNPROCESSABLE_ENTITY));
        }
        return Optional.empty();
    }

    /**
     * Checks the canonical table, column and type against the live metadata.
     *
     * @param dataSource the already-resolved datasource
     * @param candidate the candidate rule
     * @return the canonical rule, or the rejection
     */
    private Inspection inspect(DataSource dataSource, DataScopeRule candidate) {
        String canonicalAttribute =
                PrincipalAttribute.fromCode(candidate.principalAttribute())
                        .map(PrincipalAttribute::code)
                        .orElse(null);
        if (candidate.principalAttribute() != null && canonicalAttribute == null) {
            return Inspection.reject(
                    new Denial(
                            REASON_UNKNOWN_ATTRIBUTE + echo(candidate.principalAttribute()),
                            HttpStatus.UNPROCESSABLE_ENTITY));
        }
        TableSchema table = findTable(dataSource, candidate.tableName());
        if (table == null) {
            return Inspection.reject(
                    new Denial(
                            REASON_TABLE_MISSING + echo(candidate.tableName()),
                            HttpStatus.UNPROCESSABLE_ENTITY));
        }
        String canonicalTable = table.name();
        if (candidate.columnName() == null) {
            return Inspection.of(
                    withNames(candidate, canonicalTable, null, canonicalAttribute));
        }
        Optional<ColumnSchema> column = findColumn(table, candidate.columnName());
        if (column.isEmpty()) {
            return Inspection.reject(
                    new Denial(
                            REASON_COLUMN_MISSING + echo(candidate.columnName()),
                            HttpStatus.UNPROCESSABLE_ENTITY));
        }
        ColumnSchema found = column.get();
        PrincipalAttribute attribute =
                canonicalAttribute == null ? null : PrincipalAttribute.fromCode(canonicalAttribute).get();
        if (attribute != null && !typeCompatible(found.typeName(), attribute)) {
            return Inspection.reject(
                    new Denial(
                            REASON_TYPE_MISMATCH
                                    + canonicalTable
                                    + "."
                                    + found.name()
                                    + "（"
                                    + found.typeDisplay()
                                    + "）与 "
                                    + attribute.code(),
                            HttpStatus.UNPROCESSABLE_ENTITY));
        }
        return Inspection.of(
                withNames(candidate, canonicalTable, found.name(), canonicalAttribute));
    }

    /**
     * Reads the datasource's live metadata and finds one table, case-insensitively.
     *
     * @param dataSource the already-resolved datasource
     * @param tableName the table name
     * @return the table, or {@code null} when absent
     */
    private TableSchema findTable(DataSource dataSource, String tableName) {
        List<TableSchema> tables = introspector.introspect(dataSource);
        for (TableSchema table : tables) {
            if (table.name() != null && table.name().equalsIgnoreCase(tableName)) {
                return table;
            }
        }
        return null;
    }

    private static Optional<ColumnSchema> findColumn(TableSchema table, String columnName) {
        for (ColumnSchema column : table.columns()) {
            if (column.name() != null && column.name().equalsIgnoreCase(columnName)) {
                return Optional.of(column);
            }
        }
        return Optional.empty();
    }

    /**
     * Reports whether a column type can hold the attribute's value.
     *
     * <p>{@link PrincipalAttribute#USER_ID user id}, {@code username}, {@code studentNo} and the id
     * values inside {@code classIds} are compared as text, so any type renders (the database coerces
     * or compares textually); only the single-valued numeric attributes, which are rendered as a bare
     * number, must target a numeric column. A type outside both known sets is not rejected: unknown
     * dialects are left to the policy engine rather than blocked here.
     *
     * @param typeName the introspected type name
     * @param attribute the attribute
     * @return {@code false} only for a known numeric attribute on a known text column
     */
    static boolean typeCompatible(String typeName, PrincipalAttribute attribute) {
        if (attribute == null || attribute == MULTIVALUED_ATTRIBUTE) {
            // classIds is an IN list of values, rendered textually.
            return true;
        }
        boolean numericAttribute =
                attribute == PrincipalAttribute.STUDENT_ID;
        if (!numericAttribute) {
            return true;
        }
        String type = normalizeType(typeName);
        if (!TEXT_TYPES.contains(type)) {
            return true;
        }
        return false;
    }

    /** Strips a size/precision suffix and upper-cases the type name. */
    private static String normalizeType(String typeName) {
        if (typeName == null) {
            return "";
        }
        String upper = typeName.trim().toUpperCase(Locale.ROOT);
        int paren = upper.indexOf('(');
        if (paren > 0) {
            upper = upper.substring(0, paren);
        }
        // H2 and SQL-standard drivers report VARCHAR as CHARACTER VARYING (and CHAR as CHARACTER).
        if (upper.equals("CHARACTER VARYING") || upper.equals("CHAR VARYING")) {
            return "VARCHAR";
        }
        if (upper.equals("CHARACTER")) {
            return "CHAR";
        }
        if (upper.equals("NATIONAL CHARACTER VARYING")) {
            return "NVARCHAR";
        }
        return upper;
    }

    private static DataScopeRule withNames(
            DataScopeRule rule, String tableName, String columnName, String principalAttribute) {
        return new DataScopeRule(
                rule.id(),
                rule.roleId(),
                rule.datasourceId(),
                tableName,
                rule.policy(),
                columnName,
                principalAttribute);
    }

    /**
     * Resolves the datasource pool for the introspection pass.
     *
     * <p>The datasource was validated a moment earlier; a removal in between fails the change closed
     * as an introspection error rather than silently storing a rule.
     *
     * @param datasourceId the datasource id
     * @return the pool
     */
    private DataSource resolveDatasource(String datasourceId) {
        return datasources.resolve(datasourceId);
    }

    // --- mutation ---------------------------------------------------------------------------

    /** Runs the upsert and its SUCCESS audit in one transaction, after validation. */
    private Mono<DataScopeRule> store(String actorId, DataScopeRule rule) {
        return Mono.defer(() -> apply(actorId, rule)).as(transactions::transactional);
    }

    /** Inserts or updates the rule and records the SUCCESS row, in the caller's transaction. */
    private Mono<DataScopeRule> apply(String actorId, DataScopeRule requested) {
        return repository
                .dataScope(requested.roleId(), requested.datasourceId(), requested.tableName())
                .flatMap(before -> upsertAndAudit(actorId, before, requested))
                .switchIfEmpty(Mono.defer(() -> upsertAndAudit(actorId, null, requested)));
    }

    private Mono<DataScopeRule> upsertAndAudit(
            String actorId, DataScopeRule before, DataScopeRule requested) {
        return Mono.defer(
                        () ->
                                repository.upsertDataScope(
                                        requested.roleId(),
                                        requested.datasourceId(),
                                        requested.tableName(),
                                        requested.policy(),
                                        requested.columnName(),
                                        requested.principalAttribute()))
                .flatMap(after -> audit(actorId, ACTION_UPSERT, before, after).thenReturn(after));
    }

    /**
     * Deletes the rule and records its SUCCESS row, both in the caller's transaction.
     *
     * <p>The row was loaded just before, but a concurrent delete can still win the race; the reported
     * row count turns that into the same {@code 404} rather than a silent, unaudited success.
     */
    private Mono<DataScopeRule> deleteAndAudit(String actorId, String id, DataScopeRule before) {
        return repository
                .deleteDataScope(id)
                .flatMap(
                        deleted ->
                                deleted != null && deleted > 0
                                        ? audit(actorId, ACTION_DELETE, before, null).thenReturn(before)
                                        : deny(actorId, ACTION_DELETE, id, before, null, missing()));
    }

    private static Denial missing() {
        return new Denial(REASON_SCOPE_MISSING, HttpStatus.NOT_FOUND);
    }

    // --- parsing and identifier checks -------------------------------------------------------

    /**
     * Parses the policy, rejecting anything outside {@code ALL}/{@code FILTER}/{@code DENY}.
     *
     * @param policy the requested policy
     * @return the policy
     */
    private static DataScopePolicy parsePolicy(String policy) {
        String value = policy == null ? null : policy.trim();
        if (value != null) {
            for (DataScopePolicy candidate : DataScopePolicy.values()) {
                if (candidate.name().equalsIgnoreCase(value)) {
                    return candidate;
                }
            }
        }
        throw badRequest(REASON_POLICY_INVALID);
    }

    /** Reports whether a value is a bare, unqualified SQL identifier. */
    private static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER_PATTERN.matcher(value).matches();
    }

    /** Validates an id field (the role id or the rule path id). */
    private static void requireId(String value, String message) {
        if (value == null || value.isBlank() || !ID_PATTERN.matcher(value).matches()) {
            throw badRequest(message);
        }
    }

    /**
     * Validates a datasource id against the (wider) registry id space. The id is only bound as a
     * parameter; it is never interpolated into SQL.
     */
    private static void requireDatasourceId(String value) {
        if (value == null || !DATASOURCE_ID_PATTERN.matcher(value).matches()) {
            throw badRequest("数据源标识无效");
        }
    }

    // --- audit ------------------------------------------------------------------------------

    /**
     * Appends the SUCCESS row for a completed mutation.
     *
     * <p>An audit-insert failure is <em>not</em> swallowed: it propagates so the enclosing
     * transaction rolls the mutation back. Callers only see a failed request, never a silently
     * unaudited change.
     */
    private Mono<Void> audit(
            String actorId, String action, DataScopeRule before, DataScopeRule after) {
        String entityId = after != null ? after.id() : (before == null ? null : before.id());
        return repository.record(
                actorId,
                action,
                ENTITY_TYPE,
                entityId,
                json(before),
                json(after),
                RESULT_SUCCESS,
                null);
    }

    /**
     * Appends a DENIED row for a rejected mutation, then fails with that rejection.
     *
     * <p>Runs in its own nested transaction so the row survives any outer transaction rolling back
     * with the rejection. Best effort: a rejection must still reach the caller with its status, so an
     * audit failure is logged rather than replacing the denial.
     */
    private Mono<DataScopeRule> deny(
            String actorId,
            String action,
            String entityId,
            DataScopeRule before,
            DataScopeRule requested,
            Denial denial) {
        Mono<Void> record =
                repository
                        .record(
                                actorId,
                                action,
                                ENTITY_TYPE,
                                entityId,
                                jsonForDenial(before),
                                jsonForDenial(requested),
                                RESULT_DENIED,
                                truncate(denial.getMessage()))
                        .onErrorResume(
                                error -> {
                                    log.error(
                                            "Failed to append the DENIED audit row {}",
                                            action,
                                            error);
                                    return Mono.empty();
                                });
        return record.as(newTransactions::transactional).then(Mono.error(denial));
    }

    /**
     * Serializes an audit payload for a <em>rejected</em> request. Unlike the success path this must
     * never fail: the rejection's status has to reach the client and the DENIED row must still be
     * written, so a serialization failure stores {@code null}.
     */
    private String jsonForDenial(DataScopeRule rule) {
        try {
            return json(rule);
        } catch (IllegalStateException ex) {
            log.warn("Failed to serialize the DENIED audit payload; storing null instead", ex);
            return null;
        }
    }

    private String json(DataScopeRule rule) {
        if (rule == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(new AuditDataScope(rule));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize the access-audit payload", ex);
        }
    }

    /**
     * The audited projection of a rule: exactly the fields {@link DataScopeView} exposes. Also used
     * to describe a request that was rejected.
     */
    private record AuditDataScope(
            String id,
            String roleId,
            String datasourceId,
            String tableName,
            String policy,
            String columnName,
            String principalAttribute) {

        private AuditDataScope(DataScopeRule rule) {
            this(
                    rule.id(),
                    rule.roleId(),
                    rule.datasourceId(),
                    rule.tableName(),
                    rule.policy() == null ? null : rule.policy().name(),
                    rule.columnName(),
                    rule.principalAttribute());
        }
    }

    /** Shortens an echoed name so a hostile body cannot inflate the response or the audit reason. */
    private static String echo(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_ECHOED_LENGTH
                ? value
                : value.substring(0, MAX_ECHOED_LENGTH) + "…";
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= MAX_REASON_LENGTH
                ? reason
                : reason.substring(0, MAX_REASON_LENGTH);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * A rejected data-scope change, carrying the reason and the HTTP status. Carried through the
     * reactive chain so the enclosing transaction can write its {@code DENIED} audit row.
     */
    private static final class Denial extends RuntimeException {

        private final transient HttpStatus status;

        private Denial(String reason, HttpStatus status) {
            super(reason, null, false, false);
            this.status = status;
        }

        private ResponseStatusException toException() {
            return new ResponseStatusException(status, getMessage());
        }
    }
}
