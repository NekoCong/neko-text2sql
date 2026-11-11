package com.nekocong.text2sql.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.api.dto.DatasourceMatrixDatasourceView;
import com.nekocong.text2sql.access.api.dto.DatasourceMatrixRoleView;
import com.nekocong.text2sql.access.api.dto.DatasourceMatrixView;
import com.nekocong.text2sql.access.api.dto.RoleDatasourcesView;
import com.nekocong.text2sql.datasource.DataSourceDefinition;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * The role → datasource matrix: read the full matrix, read one role's own grant set, and replace it.
 *
 * <p>Kept separate from {@link RolePermissionService} (permissions) and {@link DataScopeService}
 * (data scopes) so each stays focused. The transaction and audit patterns are the same:
 *
 * <ul>
 *   <li>A replace runs in one reactive transaction: the set replacement (delete + inserts) and its
 *       {@code role.datasources} {@code SUCCESS} audit row commit together or not at all, so a failed
 *       audit insert also rolls the set back.
 *   <li>A rejection decided against the loaded role (unknown role, unknown datasource) writes exactly
 *       one {@code DENIED} row in its own {@code REQUIRES_NEW} transaction, then fails with the mapped
 *       status.
 *   <li>Shape validation (a malformed role id, a missing body, a malformed datasource id, too many
 *       ids) fails with {@code 400} before the role is loaded and writes no row: there is no entity to
 *       attribute the attempt to.
 * </ul>
 *
 * <p>An omitted body or a {@code null} {@code datasourceIds} field is <em>not</em> an empty set: only
 * an explicit {@code []} clears the role, so a client bug cannot silently revoke every grant.
 *
 * <p>{@code access_role_datasource} is the single enforcement source (read by {@code
 * DatasourceAccessService} through the resolver), so a change is effective on the next request
 * without a new login. Admins bypass datasource grants by definition, so no rows are synthesised for
 * them; a stored grant on an admin role is still shown because it is harmless.
 */
@Service
public class RoleDatasourceService {

    private static final Logger log = LoggerFactory.getLogger(RoleDatasourceService.class);

    /** The alphabet role ids are generated from (UUIDs and the deterministic seed ids). */
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** The datasource id space (dots, dashes, underscores); bound, never interpolated. */
    private static final Pattern DATASOURCE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /** Upper bound on distinct ids per replace request, so a huge body cannot drive unbounded work. */
    static final int MAX_DATASOURCE_IDS = 256;

    /** Matches {@code access_audit.reason VARCHAR(512)}. */
    private static final int MAX_REASON_LENGTH = 512;

    private static final String ENTITY_TYPE = "ROLE";
    private static final String ACTION_DATASOURCES = "role.datasources";
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_DENIED = "DENIED";

    private static final String REASON_ROLE_MISSING = "角色不存在";
    private static final String REASON_BODY_MISSING = "datasourceIds 不能为空";
    private static final String REASON_MALFORMED = "数据源列表格式不正确";
    private static final String REASON_DATASOURCE_ID_INVALID = "数据源标识无效";
    private static final String REASON_TOO_MANY_DATASOURCES =
            "数据源数量不能超过 " + MAX_DATASOURCE_IDS + " 个";
    private static final String REASON_DATASOURCE_MISSING = "数据源不存在：";

    private final AccessAdminRepository repository;
    private final DataSourceRegistry datasources;
    private final TransactionalOperator transactions;
    private final TransactionalOperator newTransactions;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service.
     *
     * @param repository the admin repository
     * @param datasources the target-datasource registry, to validate ids and build the matrix
     * @param transactionManager the R2DBC reactive transaction manager
     * @param objectMapper the shared Jackson mapper, used for the audit JSON payloads
     */
    public RoleDatasourceService(
            AccessAdminRepository repository,
            DataSourceRegistry datasources,
            ReactiveTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.datasources = datasources;
        this.transactions = TransactionalOperator.create(transactionManager);
        this.newTransactions =
                TransactionalOperator.create(
                        transactionManager,
                        new DefaultTransactionDefinition(
                                TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        this.objectMapper = objectMapper;
    }

    /**
     * Reads the full matrix: every role (ordered by code) with its sorted grant set, and every
     * registered datasource.
     *
     * <p>A stored grant for a datasource that is no longer registered is ignored (not an error), so
     * a runtime-removed datasource cannot break the page.
     *
     * @return the matrix
     */
    public Mono<DatasourceMatrixView> matrix() {
        return Mono.zip(repository.roles(), repository.datasourceGrants())
                .map(tuple -> build(tuple.getT1(), tuple.getT2()));
    }

    /**
     * Reads one role's own datasource grant set.
     *
     * <p>An unknown role is a {@code 404}; reads are not audited.
     *
     * @param roleId the role id
     * @return the role's datasource ids, ordered
     */
    public Mono<RoleDatasourcesView> datasourcesOf(String roleId) {
        return Mono.defer(
                () -> {
                    requireIdentifier(roleId);
                    Set<String> registered = registeredIds();
                    return repository
                            .role(roleId)
                            .switchIfEmpty(Mono.error(notFound()))
                            .then(repository.datasourceIdsOf(roleId))
                            .map(ids -> new RoleDatasourcesView(
                                    roleId, visibleIds(ids, registered)));
                });
    }

    /**
     * Replaces one role's datasource grant set.
     *
     * <p>The <em>whole</em> set is replaced: a previously granted datasource absent from the request
     * is revoked. Only an explicit empty list clears the role; a missing body or a {@code null} {@code
     * datasourceIds} field is rejected with {@code 400} so a malformed client cannot wipe the set.
     *
     * @param actorId the acting user id
     * @param roleId the role id
     * @param request the complete new set; {@code null} means the body or field was absent
     * @return the stored set
     */
    public Mono<RoleDatasourcesView> replaceDatasources(
            String actorId, String roleId, List<String> request) {
        return Mono.defer(
                        () -> {
                            // Shape-only checks first: they need no entity, so they are not audited.
                            requireIdentifier(roleId);
                            if (request == null) {
                                throw badRequest(REASON_BODY_MISSING);
                            }
                            List<String> requestedIds = normalizeIds(request);
                            Mono<AccessRole> loaded =
                                    repository
                                            .role(roleId)
                                            .switchIfEmpty(
                                                    Mono.defer(
                                                            () ->
                                                                    Mono.defer(
                                                                                    () ->
                                                                                            denyUnknownRole(
                                                                                                            actorId,
                                                                                                            roleId,
                                                                                                            requestedIds))
                                                                            .cast(AccessRole.class)));
                            return loaded.flatMap(
                                    role ->
                                            replace(actorId, role, requestedIds)
                                                    .onErrorMap(
                                                            Denial.class,
                                                            RoleDatasourceService::asStatus));
                        })
                .as(transactions::transactional)
                // Denials raised by either branch (including the unknown role) map to their status.
                .onErrorMap(Denial.class, RoleDatasourceService::asStatus)
                .map(ids -> new RoleDatasourcesView(roleId, ids));
    }

    /** Applies a shape-valid replace request to a loaded role. */
    private Mono<List<String>> replace(
            String actorId, AccessRole role, List<String> requestedIds) {
        Set<String> registered = registeredIds();
        // The current set is read first so a DENIED row reports the real before state.
        return repository
                .datasourceIdsOf(role.id())
                .flatMap(before -> {
                    List<String> beforeIds = sortedIds(before);
                    for (String id : requestedIds) {
                        if (!registered.contains(id)) {
                            return deny(
                                    actorId,
                                    role.id(),
                                    beforeIds,
                                    requestedIds,
                                    new Denial(
                                            REASON_DATASOURCE_MISSING + id,
                                            HttpStatus.NOT_FOUND));
                        }
                    }
                    return apply(actorId, role.id(), beforeIds, requestedIds);
                });
    }

    /** Replaces the set and records the SUCCESS row, in the caller's transaction. */
    private Mono<List<String>> apply(
            String actorId, String roleId, List<String> beforeIds, List<String> requestedIds) {
        return Mono.defer(() -> repository.replaceRoleDatasources(roleId, requestedIds))
                .then(
                        repository.record(
                                actorId,
                                ACTION_DATASOURCES,
                                ENTITY_TYPE,
                                roleId,
                                jsonIds(beforeIds),
                                jsonIds(requestedIds),
                                RESULT_SUCCESS,
                                null))
                .thenReturn(requestedIds);
    }

    /**
     * Appends the {@code DENIED} row for an unknown role, then fails with {@code 404}.
     *
     * @param actorId the acting user id
     * @param roleId the requested role id
     * @param requestedIds the requested ids
     * @return never completes normally
     */
    private Mono<List<String>> denyUnknownRole(
            String actorId, String roleId, List<String> requestedIds) {
        return deny(
                actorId,
                roleId,
                List.of(),
                requestedIds,
                new Denial(REASON_ROLE_MISSING, HttpStatus.NOT_FOUND));
    }

    /**
     * Appends the {@code DENIED} row in its own transaction, then fails with the denial.
     *
     * <p>The audit insert is best effort: a rejection must still reach the caller with its status, so
     * an audit failure is logged rather than replacing the denial.
     */
    private Mono<List<String>> deny(
            String actorId,
            String roleId,
            List<String> beforeIds,
            List<String> requestedIds,
            Denial denial) {
        Mono<Void> record =
                repository
                        .record(
                                actorId,
                                ACTION_DATASOURCES,
                                ENTITY_TYPE,
                                roleId,
                                jsonIdsForDenial(beforeIds),
                                jsonIdsForDenial(requestedIds),
                                RESULT_DENIED,
                                truncate(denial.getMessage()))
                        .onErrorResume(
                                error -> {
                                    log.error(
                                            "Failed to append the DENIED audit row {}",
                                            ACTION_DATASOURCES,
                                            error);
                                    return Mono.empty();
                                });
        return record.as(newTransactions::transactional).then(Mono.error(denial));
    }

    // --- matrix building -------------------------------------------------------------------

    private DatasourceMatrixView build(List<AccessRole> roles, List<RoleDatasource> grants) {
        Set<String> registered = registeredIds();
        Map<String, List<String>> byRole = new HashMap<>();
        for (RoleDatasource grant : grants) {
            byRole.computeIfAbsent(grant.roleId(), key -> new ArrayList<>())
                    .add(grant.datasourceId());
        }
        // repository.roles() is already ordered by code.
        List<DatasourceMatrixRoleView> roleViews =
                roles.stream()
                        .map(
                                role ->
                                        new DatasourceMatrixRoleView(
                                                role.id(),
                                                role.code(),
                                                role.name(),
                                                role.builtIn(),
                                                role.enabled(),
                                                visibleIds(
                                                        byRole.getOrDefault(role.id(), List.of()),
                                                        registered)))
                        .toList();
        String defaultId = datasources.defaultId();
        List<DatasourceMatrixDatasourceView> datasourceViews =
                datasources.definitions().stream()
                        .map(
                                definition ->
                                        new DatasourceMatrixDatasourceView(
                                                definition.getId(),
                                                displayName(definition),
                                                definition.getId().equals(defaultId)))
                        .toList();
        return new DatasourceMatrixView(roleViews, datasourceViews);
    }

    private static String displayName(DataSourceDefinition definition) {
        return definition.getName() == null ? definition.getId() : definition.getName();
    }

    private Set<String> registeredIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (DataSourceDefinition definition : datasources.definitions()) {
            ids.add(definition.getId());
        }
        return ids;
    }

    /** Keeps only ids still registered, sorted and free of duplicates. */
    private static List<String> visibleIds(List<String> ids, Set<String> registered) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .filter(registered::contains)
                .distinct()
                .sorted()
                .toList();
    }

    // --- validation ------------------------------------------------------------------------

    /**
     * Validates the shape of a requested id set: trims each id, rejects an over-long or malformed id,
     * and returns the de-duplicated ids in canonical (sorted) order.
     *
     * <p>The size cap is applied <em>after</em> de-duplication. Existence is checked against the
     * registry afterwards, because that denial depends on the loaded role and is audited.
     */
    static List<String> normalizeIds(List<String> request) {
        if (request.isEmpty()) {
            return List.of();
        }
        TreeSet<String> ids = new TreeSet<>();
        for (String raw : request) {
            if (raw == null) {
                throw badRequest(REASON_MALFORMED);
            }
            String id = raw.trim();
            if (id.isEmpty()) {
                throw badRequest(REASON_MALFORMED);
            }
            if (!DATASOURCE_ID_PATTERN.matcher(id).matches()) {
                throw badRequest(REASON_DATASOURCE_ID_INVALID);
            }
            ids.add(id);
        }
        if (ids.size() > MAX_DATASOURCE_IDS) {
            throw badRequest(REASON_TOO_MANY_DATASOURCES);
        }
        return List.copyOf(ids);
    }

    private static List<String> sortedIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static void requireIdentifier(String roleId) {
        if (roleId == null || roleId.isBlank() || !ID_PATTERN.matcher(roleId).matches()) {
            throw badRequest("角色标识无效");
        }
    }

    /** Serializes an id array for the DENIED payload; never fails the rejection. */
    private String jsonIdsForDenial(List<String> ids) {
        try {
            return jsonIds(ids);
        } catch (IllegalStateException ex) {
            log.warn("Failed to serialize the DENIED audit payload; storing null instead", ex);
            return null;
        }
    }

    private String jsonIds(List<String> ids) {
        if (ids == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize the access-audit payload", ex);
        }
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= MAX_REASON_LENGTH
                ? reason
                : reason.substring(0, MAX_REASON_LENGTH);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, REASON_ROLE_MISSING);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /** Maps an audited rejection onto the response status it carries. */
    private static Throwable asStatus(Denial denial) {
        return denial.toException();
    }

    /**
     * A rejected datasource-set change, carrying the reason and the HTTP status. Internal: rejected
     * requests are rendered by the controller through {@link #asStatus}.
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
