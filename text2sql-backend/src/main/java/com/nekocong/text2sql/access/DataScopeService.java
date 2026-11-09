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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Pattern;
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
 *   <li>A rejection decided against loaded data (unknown role, unknown datasource, unknown table or
 *       column, unknown attribute) writes exactly one {@code DENIED} row in its own {@code
 *       REQUIRES_NEW} transaction, then fails with the mapped status ({@code 404} / {@code 422}).
 *   <li>Shape validation (missing or malformed identifiers, a bad policy, a {@code FILTER} without a
 *       column/attribute, an {@code ALL}/{@code DENY} carrying one) fails with {@code 400} before
 *       anything is loaded and writes no row: there is no entity to attribute the attempt to.
 * </ul>
 *
 * <p>Fail closed, and never trust the body as SQL: the only names that can reach a later predicate
 * are a table and column validated against the datasource's <em>live</em> metadata and an attribute
 * validated against the closed {@link PrincipalAttribute} catalog. The live metadata is blocking JDBC
 * ({@link SchemaIntrospector#introspect}), so it is read on the {@code blockingScheduler}, never on
 * the event loop.
 */
@Service
public class DataScopeService {

    private static final Logger log = LoggerFactory.getLogger(DataScopeService.class);

    /** A bare, unqualified SQL identifier: what a table or column name may be. */
    static final String IDENTIFIER_PATTERN_DESCRIPTION = "[A-Za-z_][A-Za-z0-9_]*";

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^" + IDENTIFIER_PATTERN_DESCRIPTION + "$");

    /** The alphabet ids are generated from (UUIDs and the deterministic seed ids). */
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** Matches {@code access_audit.reason VARCHAR(512)}. */
    private static final int MAX_REASON_LENGTH = 512;

    /** How much of an echoed name to include in a rejection message. */
    private static final int MAX_ECHOED_LENGTH = 64;

    private static final String ENTITY_TYPE = "DATA_SCOPE";
    private static final String ACTION_UPSERT = "data_scope.upsert";
    private static final String ACTION_DELETE = "data_scope.delete";

    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_DENIED = "DENIED";

    private static final String REASON_ROLE_MISSING = "角色不存在";
    private static final String REASON_DATASOURCE_MISSING = "数据源不存在";
    private static final String REASON_SCOPE_MISSING = "数据范围不存在";
    private static final String REASON_POLICY_INVALID = "策略类型无效";
    private static final String REASON_FILTER_INCOMPLETE = "FILTER 策略必须指定过滤列和主体属性";
    private static final String REASON_NON_FILTER_HAS_COLUMN = "ALL/DENY 策略不能指定过滤列或主体属性";
    private static final String REASON_TABLE_MISSING = "表不存在：";
    private static final String REASON_COLUMN_MISSING = "列不存在：";
    private static final String REASON_UNKNOWN_ATTRIBUTE = "未知主体属性：";

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
     * @param datasources the target-datasource registry, to validate the datasource id
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
     * <p>An unknown role is a {@code 404}; reads are not audited.
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
     * <p>An existing rule keeps its id; only the policy, column and attribute change.
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
                                    .then(Mono.defer(() -> resolveAttribute(actorId, request, candidate)))
                                    .flatMap(
                                            rule ->
                                                    requireTableAndColumn(actorId, rule)
                                                            .then(
                                                                    Mono.defer(
                                                                            () -> apply(actorId, rule))));
                        })
                .as(transactions::transactional)
                // A denial (audited in its own transaction) maps to its status here.
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
                                    .flatMap(before -> deleteAndAudit(actorId, id, before))
                                    .then();
                        })
                .as(transactions::transactional)
                .onErrorMap(Denial.class, Denial::toException);
    }

    /**
     * Deletes the rule and records its SUCCESS row, both in the caller's transaction.
     *
     * <p>The row was loaded just above, but a concurrent delete can still win the race; the reported
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

    // --- shape validation (400, not audited) ------------------------------------------------

    /**
     * Applies the checks that need no stored entity. These are the only rejections that answer
     * {@code 400} without auditing: they run before anything is loaded, so there is no entity to
     * attribute the attempt to.
     *
     * <p>Every identifier that can later reach SQL (the table and column) must be a bare
     * {@code [A-Za-z_][A-Za-z0-9_]*} name — no quotes, dots, spaces or expressions, so a raw
     * predicate has no way in. The role and datasource ids use the wider generated-id alphabet; they
     * are only ever bound as parameters.
     *
     * @param request the body
     */
    private static void requireShape(DataScopeRequest request) {
        if (request == null) {
            throw badRequest("请求体不能为空");
        }
        requireId(request.roleId(), "角色标识无效");
        requireId(request.datasourceId(), "数据源标识无效");
        requireIdentifier(request.tableName());
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
        if (columnName != null) {
            requireIdentifier(columnName);
        }
    }

    /** Builds the requested rule (no id, attribute canonicalised later) from a shape-valid body. */
    private static DataScopeRule candidate(DataScopeRequest request) {
        return new DataScopeRule(
                null,
                request.roleId(),
                request.datasourceId(),
                request.tableName(),
                parsePolicy(request.policy()),
                blankToNull(request.columnName()),
                null);
    }

    // --- referenced-data validation (audited denials) ---------------------------------------

    /**
     * Validates the principal attribute against the closed catalog and returns the rule carrying its
     * canonical code.
     *
     * <p>This answers {@code 422} rather than {@code 400} on purpose: the value is well-formed but
     * references something outside the catalog, so it is a denial decided against the closed set, not a
     * malformed request. It is audited accordingly.
     */
    private Mono<DataScopeRule> resolveAttribute(
            String actorId, DataScopeRequest request, DataScopeRule candidate) {
        String attribute = blankToNull(request.principalAttribute());
        if (attribute == null) {
            return Mono.just(candidate);
        }
        Optional<PrincipalAttribute> resolved = PrincipalAttribute.fromCode(attribute);
        if (resolved.isPresent()) {
            String code = resolved.get().code();
            return Mono.just(
                    new DataScopeRule(
                            candidate.id(),
                            candidate.roleId(),
                            candidate.datasourceId(),
                            candidate.tableName(),
                            candidate.policy(),
                            candidate.columnName(),
                            code));
        }
        return deny(
                        actorId,
                        ACTION_UPSERT,
                        null,
                        null,
                        candidate,
                        new Denial(
                                REASON_UNKNOWN_ATTRIBUTE + echo(attribute),
                                HttpStatus.UNPROCESSABLE_ENTITY))
                .then(Mono.empty());
    }

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
     * Requires the datasource to exist in the registry. A registry miss is a {@code 404}, audited.
     *
     * <p>Resolution is offloaded to the blocking scheduler: {@code resolve} is {@code synchronized}
     * over the pool map, so it can wait behind a concurrent registration.
     */
    private Mono<Void> requireDatasource(String actorId, DataScopeRule requested) {
        return Mono.<Void>fromRunnable(() -> datasources.resolve(requested.datasourceId()))
                .subscribeOn(blockingScheduler)
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
                                        .then());
    }

    /**
     * Requires the table (and, for {@code FILTER}, the column) to exist in the datasource's live
     * metadata. Failures are {@code 422}, audited.
     *
     * <p>One introspection pass answers both checks, because it is the expensive part. Introspection
     * is blocking JDBC, so it runs on the blocking scheduler; an introspection failure propagates
     * (fail closed) rather than storing a policy against unverified metadata.
     */
    private Mono<Void> requireTableAndColumn(String actorId, DataScopeRule requested) {
        return Mono.fromCallable(() -> inspect(requested))
                .subscribeOn(blockingScheduler)
                .flatMap(
                        denial ->
                                denial.<Mono<Void>>map(
                                                rejected ->
                                                        deny(
                                                                        actorId,
                                                                        ACTION_UPSERT,
                                                                        null,
                                                                        null,
                                                                        requested,
                                                                        rejected)
                                                                .then())
                                        .orElseGet(Mono::empty));
    }

    /**
     * Reads the datasource's live metadata and reports why the rule is invalid, if it is.
     *
     * @param requested the requested rule
     * @return the rejection, or empty when the table and column exist
     */
    private Optional<Denial> inspect(DataScopeRule requested) {
        TableSchema table = findTable(requested.datasourceId(), requested.tableName());
        if (table == null) {
            return Optional.of(
                    new Denial(
                            REASON_TABLE_MISSING + echo(requested.tableName()),
                            HttpStatus.UNPROCESSABLE_ENTITY));
        }
        if (requested.columnName() != null && !hasColumn(table, requested.columnName())) {
            return Optional.of(
                    new Denial(
                            REASON_COLUMN_MISSING + echo(requested.columnName()),
                            HttpStatus.UNPROCESSABLE_ENTITY));
        }
        return Optional.empty();
    }

    /**
     * Reads the datasource's live metadata and finds one table, case-insensitively.
     *
     * @param datasourceId the datasource id (already validated against the registry)
     * @param tableName the table name (already a bare identifier)
     * @return the table, or {@code null} when absent
     */
    private TableSchema findTable(String datasourceId, String tableName) {
        List<TableSchema> tables = introspector.introspect(datasources.resolve(datasourceId));
        for (TableSchema table : tables) {
            if (table.name() != null && table.name().equalsIgnoreCase(tableName)) {
                return table;
            }
        }
        return null;
    }

    private static boolean hasColumn(TableSchema table, String columnName) {
        for (ColumnSchema column : table.columns()) {
            if (column.name() != null && column.name().equalsIgnoreCase(columnName)) {
                return true;
            }
        }
        return false;
    }

    // --- mutation ---------------------------------------------------------------------------

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

    /**
     * Rejects anything that is not a bare, unqualified identifier. This is the gate between the body
     * and a name that may later be rendered into SQL, so it is deliberately strict: no quotes, no
     * dots, no spaces, no expressions.
     */
    private static void requireIdentifier(String value) {
        if (value == null || !IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw badRequest("标识无效：" + echo(value));
        }
    }

    /** Validates an id field (role, datasource, path) against the wider id alphabet. */
    private static void requireId(String value, String message) {
        if (value == null || value.isBlank() || !ID_PATTERN.matcher(value).matches()) {
            throw badRequest(message);
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
     * <p>Runs in its own nested transaction so the row survives the outer mutation transaction
     * rolling back with the rejection. Best effort: a rejection must still reach the caller with its
     * status, so an audit failure is logged rather than replacing the denial.
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
