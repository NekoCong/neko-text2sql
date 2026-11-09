package com.nekocong.text2sql.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.api.dto.AccessPermissionView;
import com.nekocong.text2sql.access.api.dto.AccessRolePermissionsView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * The permission catalog and the role-permission matrix: read the catalog, read one role's own
 * permission set, and replace it.
 *
 * <p>Kept separate from {@link AccessAdminService} (roles, users, data scopes) so each stays focused.
 * The transaction and audit patterns are the same:
 *
 * <ul>
 *   <li>A replace runs in one reactive transaction: the set replacement (delete + inserts) and its
 *       {@code role.permissions} {@code SUCCESS} audit row commit together or not at all, so a failed
 *       audit insert also rolls the set back.
 *   <li>A rejection decided against the loaded role (unknown role, unknown catalog key, denied by
 *       the anti-escalation guard) writes exactly one {@code DENIED} row in its own {@code
 *       REQUIRES_NEW} transaction, then fails with the mapped status.
 *   <li>Shape validation (a malformed path id, a missing body, too many keys) fails with {@code 400}
 *       before the role is loaded and writes no row: there is no entity to attribute the attempt to.
 * </ul>
 *
 * <p>An omitted body or a {@code null} {@code permissions} field is <em>not</em> an empty set: only an
 * explicit {@code []} clears the role, so a client bug cannot silently revoke every grant.
 *
 * <p>The anti-escalation rule ("an administrator may not grant a permission they lack") is the
 * {@link PermissionEscalationGuard} seam; until it is implemented the permissive guard is used, and
 * a guard bean registered later takes its place automatically.
 */
@Service
public class RolePermissionService {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionService.class);

    /** The alphabet role ids are generated from (UUIDs and the deterministic seed ids). */
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** Matches {@code access_permission.permission_key VARCHAR(128)}. */
    static final int MAX_PERMISSION_KEY_LENGTH = 128;

    /** Upper bound on distinct keys per replace request, so a huge body cannot drive unbounded work. */
    static final int MAX_PERMISSION_KEYS = 256;

    /** Matches {@code access_audit.reason VARCHAR(512)}. */
    private static final int MAX_REASON_LENGTH = 512;

    /** How much of an over-long key to echo in the rejection message. */
    private static final int MAX_ECHOED_KEY_LENGTH = 32;

    private static final String ENTITY_TYPE = "ROLE";
    private static final String ACTION_PERMISSIONS = "role.permissions";
    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_DENIED = "DENIED";

    private static final String REASON_ROLE_MISSING = "角色不存在";
    private static final String REASON_BODY_MISSING = "permissions 不能为空";
    private static final String REASON_TOO_MANY_PERMISSIONS =
            "权限数量不能超过 " + MAX_PERMISSION_KEYS + " 个";
    private static final String REASON_MALFORMED_PERMISSIONS = "权限列表格式不正确";

    private final AccessAdminRepository repository;
    private final TransactionalOperator transactions;
    private final TransactionalOperator newTransactions;
    private final ObjectMapper objectMapper;
    private final PermissionEscalationGuard escalationGuard;

    /**
     * Creates the service.
     *
     * @param repository the admin repository
     * @param transactionManager the R2DBC reactive transaction manager
     * @param objectMapper the shared Jackson mapper, used for the audit JSON payloads
     * @param escalationGuards the anti-escalation guard bean, or none for the permissive default
     */
    public RolePermissionService(
            AccessAdminRepository repository,
            ReactiveTransactionManager transactionManager,
            ObjectMapper objectMapper,
            ObjectProvider<PermissionEscalationGuard> escalationGuards) {
        this.repository = repository;
        this.transactions = TransactionalOperator.create(transactionManager);
        this.newTransactions =
                TransactionalOperator.create(
                        transactionManager,
                        new DefaultTransactionDefinition(
                                TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        this.objectMapper = objectMapper;
        PermissionEscalationGuard guard = escalationGuards == null ? null : escalationGuards.getIfAvailable();
        this.escalationGuard = guard == null ? PermissionEscalationGuard.permitAll() : guard;
    }

    /**
     * Lists the permission catalog, ordered by module then key.
     *
     * @return the catalog
     */
    public Mono<List<AccessPermissionView>> permissions() {
        return repository
                .permissions()
                .map(catalog -> catalog.stream().map(AccessPermissionView::of).toList());
    }

    /**
     * Reads one role's own permission set (the keys it holds directly, not inherited ones).
     *
     * <p>An unknown role is a {@code 404}; reads are not audited.
     *
     * @param roleId the role id
     * @return the role's keys, ordered
     */
    public Mono<AccessRolePermissionsView> permissionsOf(String roleId) {
        return Mono.defer(
                () -> {
                    requireIdentifier(roleId);
                    return repository
                            .role(roleId)
                            .switchIfEmpty(Mono.error(notFound()))
                            .then(repository.permissionKeysOf(roleId))
                            .map(keys -> new AccessRolePermissionsView(roleId, sortedKeys(keys)));
                });
    }

    /**
     * Replaces one role's permission set.
     *
     * <p>The <em>whole</em> set is replaced: a previously held key absent from the request is
     * revoked. Only an explicit empty list clears the role; a missing body or a {@code null} {@code
     * permissions} field is rejected with {@code 400} so a malformed client cannot wipe the set.
     *
     * @param actorId the acting user id
     * @param roleId the role id
     * @param request the complete new set; {@code null} means the body or field was absent
     * @return the stored set
     */
    public Mono<AccessRolePermissionsView> replacePermissions(
            String actorId, String roleId, List<String> request) {
        return Mono.defer(
                        () -> {
                            // Shape-only checks first: they need no entity, so they are not audited.
                            requireIdentifier(roleId);
                            if (request == null) {
                                throw badRequest(REASON_BODY_MISSING);
                            }
                            List<String> requestedKeys = normalizeKeys(request);
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
                                                                                                            requestedKeys))
                                                                            .cast(AccessRole.class)));
                            return loaded.flatMap(
                                    role ->
                                            replacePermissions(actorId, role, requestedKeys)
                                                    .onErrorMap(
                                                            Denial.class,
                                                            RolePermissionService::asStatus));
                        })
                .as(transactions::transactional)
                // Denials raised by either branch (including the unknown role) map to their status.
                .onErrorMap(Denial.class, RolePermissionService::asStatus)
                .map(keys -> new AccessRolePermissionsView(roleId, keys));
    }

    /** Applies a shape-valid replace request to a loaded role. */
    private Mono<List<String>> replacePermissions(
            String actorId, AccessRole role, List<String> requestedKeys) {
        return repository
                .permissions()
                .flatMap(catalog -> {
                    Map<String, String> idByKey = idsByKey(catalog);
                    // The current set is read first so a DENIED row reports the real before state.
                    return repository
                            .permissionKeysOf(role.id())
                            .flatMap(before -> {
                                List<String> beforeKeys = sortedKeys(before);
                                for (String key : requestedKeys) {
                                    if (!idByKey.containsKey(key)) {
                                        return deny(
                                                actorId,
                                                role.id(),
                                                beforeKeys,
                                                requestedKeys,
                                                new Denial(
                                                        "未知权限：" + echo(key),
                                                        HttpStatus.BAD_REQUEST));
                                    }
                                }
                                return guard(actorId, role.id(), requestedKeys)
                                        .flatMap(allowed ->
                                                allowed
                                                        .map(
                                                                reason ->
                                                                        deny(
                                                                                actorId,
                                                                                role.id(),
                                                                                beforeKeys,
                                                                                requestedKeys,
                                                                                new Denial(
                                                                                        reason,
                                                                                        HttpStatus
                                                                                                .FORBIDDEN)))
                                                        .orElseGet(
                                                                () ->
                                                                        apply(
                                                                                actorId,
                                                                                role.id(),
                                                                                beforeKeys,
                                                                                requestedKeys,
                                                                                idByKey)));
                            });
                });
    }

    /** Replaces the set and records the SUCCESS row, in the caller's transaction. */
    private Mono<List<String>> apply(
            String actorId,
            String roleId,
            List<String> beforeKeys,
            List<String> requestedKeys,
            Map<String, String> idByKey) {
        return Mono.defer(
                        () ->
                                repository.replaceRolePermissions(
                                        roleId,
                                        requestedKeys.stream().map(idByKey::get).toList()))
                .then(
                        repository.record(
                                actorId,
                                ACTION_PERMISSIONS,
                                ENTITY_TYPE,
                                roleId,
                                jsonKeys(beforeKeys),
                                jsonKeys(requestedKeys),
                                RESULT_SUCCESS,
                                null))
                .thenReturn(requestedKeys);
    }

    /**
     * Runs the anti-escalation guard. A guard error is not swallowed: it aborts the change (the
     * transaction rolls back) and surfaces as a server error rather than an unaudited denial. A guard
     * that wants an audited {@code 403} returns a reason instead.
     */
    private Mono<Optional<String>> guard(
            String actorId, String roleId, List<String> requestedKeys) {
        return escalationGuard
                .check(actorId, roleId, Set.copyOf(requestedKeys))
                .<Optional<String>>map(value -> value == null ? Optional.empty() : value)
                .defaultIfEmpty(Optional.empty());
    }

    /**
     * Appends the {@code DENIED} row for an unknown role, then fails with {@code 404}.
     *
     * @param actorId the acting user id
     * @param roleId the requested role id
     * @param requestedKeys the requested keys
     * @return never completes normally
     */
    private Mono<List<String>> denyUnknownRole(
            String actorId, String roleId, List<String> requestedKeys) {
        return deny(
                actorId,
                roleId,
                List.of(),
                requestedKeys,
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
            List<String> beforeKeys,
            List<String> requestedKeys,
            Denial denial) {
        Mono<Void> record =
                repository
                        .record(
                                actorId,
                                ACTION_PERMISSIONS,
                                ENTITY_TYPE,
                                roleId,
                                jsonKeysForDenial(beforeKeys),
                                jsonKeysForDenial(requestedKeys),
                                RESULT_DENIED,
                                truncate(denial.getMessage()))
                        .onErrorResume(
                                error -> {
                                    log.error(
                                            "Failed to append the DENIED audit row {}",
                                            ACTION_PERMISSIONS,
                                            error);
                                    return Mono.empty();
                                });
        return record.as(newTransactions::transactional).then(Mono.error(denial));
    }

    private static Map<String, String> idsByKey(List<AccessPermission> catalog) {
        Map<String, String> idByKey = new HashMap<>();
        for (AccessPermission permission : catalog) {
            idByKey.putIfAbsent(permission.key(), permission.id());
        }
        return idByKey;
    }

    /**
     * Validates the shape of a requested key set: trims each key, rejects an over-long key or a
     * duplicate-heavy request, and returns the de-duplicated keys in canonical order.
     *
     * <p>The size cap is applied <em>after</em> de-duplication, so repeating a few keys cannot trip
     * it. Key existence is checked against the catalog afterwards, because that denial depends on the
     * loaded role and is audited.
     */
    static List<String> normalizeKeys(List<String> request) {
        if (request.isEmpty()) {
            return List.of();
        }
        TreeSet<String> keys = new TreeSet<>();
        for (String raw : request) {
            if (raw == null) {
                throw badRequest(REASON_MALFORMED_PERMISSIONS);
            }
            String key = raw.trim();
            if (key.isEmpty()) {
                throw badRequest(REASON_MALFORMED_PERMISSIONS);
            }
            if (key.length() > MAX_PERMISSION_KEY_LENGTH) {
                throw badRequest("未知权限：" + echo(key));
            }
            keys.add(key);
        }
        if (keys.size() > MAX_PERMISSION_KEYS) {
            throw badRequest(REASON_TOO_MANY_PERMISSIONS);
        }
        return List.copyOf(keys);
    }

    private static List<String> sortedKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    /** Shortens an echoed key so a hostile body cannot inflate the response or the audit reason. */
    private static String echo(String key) {
        return key.length() <= MAX_ECHOED_KEY_LENGTH
                ? key
                : key.substring(0, MAX_ECHOED_KEY_LENGTH) + "…";
    }

    /** Serializes a key array for the DENIED payload; never fails the rejection. */
    private String jsonKeysForDenial(List<String> keys) {
        try {
            return jsonKeys(keys);
        } catch (IllegalStateException ex) {
            log.warn("Failed to serialize the DENIED audit payload; storing null instead", ex);
            return null;
        }
    }

    private String jsonKeys(List<String> keys) {
        if (keys == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(keys);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize the access-audit payload", ex);
        }
    }

    private static void requireIdentifier(String roleId) {
        if (roleId == null || roleId.isBlank() || !ID_PATTERN.matcher(roleId).matches()) {
            throw badRequest("角色标识无效");
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
     * A rejected permission-set change, carrying the reason and the HTTP status. Internal: rejected
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
