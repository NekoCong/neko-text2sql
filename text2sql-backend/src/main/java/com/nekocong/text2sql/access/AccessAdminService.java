package com.nekocong.text2sql.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.api.dto.AccessRoleRequest;
import com.nekocong.text2sql.access.api.dto.AccessRoleView;
import com.nekocong.text2sql.security.Roles;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Administrative role catalog: list, create and update (including enable/disable), with
 * single-parent inheritance validated in the application.
 *
 * <p>Rules enforced here:
 *
 * <ul>
 *   <li>{@code code} is required on create, matches {@value #CODE_PATTERN_DESCRIPTION}, and is
 *       immutable and unique afterwards.
 *   <li>{@code name} is required, at most {@value #MAX_NAME_LENGTH} characters; {@code description}
 *       is at most {@value #MAX_DESCRIPTION_LENGTH} characters (the column width).
 *   <li>{@code parentId} must reference an existing role and must not create a cycle. A disabled
 *       parent is allowed: it contributes nothing at resolution time.
 *   <li>The built-in administrator role may not be used as a parent until inheritance subset checks
 *       exist: a role holding {@code access.manage} would otherwise inherit
 *       {@code access.bypass_data_scope} from it.
 *   <li>Built-in roles keep their {@code code}; the built-in administrator role cannot be disabled.
 *       Other built-in roles may be renamed or disabled.
 * </ul>
 *
 * <p>Each mutation runs in one reactive transaction (the R2DBC {@code TransactionalOperator}): the
 * role write and its {@code access_audit} row commit together or not at all, so a failed audit insert
 * also rolls the mutation back. A rejected mutation writes exactly one {@code DENIED} row in its own
 * transaction before the rejection is returned.
 *
 * <p>Request validation that runs before a role or parent can be identified (a malformed code, a
 * missing name, an oversized description, a malformed path id) writes no audit row: there is no
 * entity to reference. Every rejection that names an existing entity, or a parent id supplied by the
 * client, is audited.
 */
@Service
public class AccessAdminService {

    private static final Logger log = LoggerFactory.getLogger(AccessAdminService.class);

    /** A role code: a lowercase letter, then lowercase letters/digits/underscore/dash (2-64 chars). */
    static final String CODE_PATTERN_DESCRIPTION = "^[a-z][a-z0-9_-]{1,63}$";

    private static final Pattern CODE_PATTERN = Pattern.compile(CODE_PATTERN_DESCRIPTION);

    /** The alphabet role ids are generated from (UUIDs and the deterministic seed ids). */
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final int MAX_NAME_LENGTH = 100;

    /** Matches {@code access_role.description VARCHAR(512)}. */
    private static final int MAX_DESCRIPTION_LENGTH = 512;

    /** Matches {@code access_audit.reason VARCHAR(512)}. */
    private static final int MAX_REASON_LENGTH = 512;

    private static final String ENTITY_TYPE = "ROLE";

    private static final String ACTION_CREATE = "role.create";
    private static final String ACTION_UPDATE = "role.update";
    private static final String ACTION_DISABLE = "role.disable";

    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_DENIED = "DENIED";

    private static final String REASON_DUPLICATE_CODE = "角色编码已存在";
    private static final String REASON_IMMUTABLE_CODE = "角色编码不可修改";
    private static final String REASON_BUILT_IN_ADMIN = "内置管理员角色不能停用";
    private static final String REASON_BUILT_IN_ADMIN_PARENT = "内置管理员角色不能作为父角色";
    private static final String REASON_CYCLE = "会形成循环继承";
    private static final String REASON_PARENT_MISSING = "父角色不存在";
    private static final String REASON_ROLE_MISSING = "角色不存在";

    private final AccessAdminRepository repository;
    private final TransactionalOperator transactions;
    private final TransactionalOperator newTransactions;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service.
     *
     * @param repository the admin repository
     * @param transactionManager the R2DBC reactive transaction manager
     * @param objectMapper the shared Jackson mapper, used for the audit JSON payloads
     */
    public AccessAdminService(
            AccessAdminRepository repository,
            ReactiveTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactions = TransactionalOperator.create(transactionManager);
        this.newTransactions =
                TransactionalOperator.create(
                        transactionManager,
                        new DefaultTransactionDefinition(
                                TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        this.objectMapper = objectMapper;
    }

    /**
     * Lists every role, ordered by code.
     *
     * @return the role views
     */
    public Mono<List<AccessRoleView>> list() {
        return repository.roles().map(roles -> roles.stream().map(AccessRoleView::of).toList());
    }

    /**
     * Creates a role.
     *
     * @param actorId the acting user id
     * @param request the request (required)
     * @return the created role
     */
    public Mono<AccessRoleView> create(String actorId, AccessRoleRequest request) {
        return Mono.defer(
                        () -> {
                            AccessRoleRequest body = request == null ? empty() : request;
                            String code = validateCode(body.code());
                            String name = validateName(body.name());
                            String description = normalizeDescription(body.description());
                            // A new role has no stored parent, so null and "" both mean "none".
                            String parentId = blankToNull(body.parentId());
                            boolean enabled = body.enabled() == null || body.enabled();
                            AccessRole requested =
                                    roleView(
                                            null,
                                            code,
                                            name,
                                            description,
                                            parentId,
                                            false,
                                            enabled);
                            return repository
                                    .roleByCode(code)
                                    .flatMap(
                                            existing ->
                                                    deny(
                                                            actorId,
                                                            ACTION_CREATE,
                                                            existing.id(),
                                                            null,
                                                            requested,
                                                            REASON_DUPLICATE_CODE))
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () -> insert(actorId, requested)))
                                    .map(AccessRoleView::of);
                        })
                .as(transactions::transactional);
    }

    /** Validates the parent, inserts the role and appends its audit row. */
    private Mono<AccessRole> insert(String actorId, AccessRole requested) {
        return requireUsableParent(requested.parentId())
                .onErrorResume(
                        Denial.class,
                        denial ->
                                deny(
                                                actorId,
                                                ACTION_CREATE,
                                                requested.parentId(),
                                                null,
                                                requested,
                                                denial.getMessage())
                                        .then())
                .then(
                        Mono.defer(
                                () ->
                                        repository.insertRole(
                                                requested.code(),
                                                requested.name(),
                                                requested.description(),
                                                requested.parentId(),
                                                requested.enabled())))
                .flatMap(role -> audit(actorId, ACTION_CREATE, null, role));
    }

    /**
     * Updates a role's mutable fields: name, description, parent and enabled.
     *
     * <p>The code may be omitted or repeated verbatim but never changed. {@code null} (or omitted)
     * name, description, parent or enabled keeps the current value; an empty-string {@code parentId}
     * clears the parent. Turning an enabled role off is audited as {@code role.disable}.
     *
     * @param actorId the acting user id
     * @param roleId the role id
     * @param request the request (an empty body keeps every field unchanged)
     * @return the updated role
     */
    public Mono<AccessRoleView> update(String actorId, String roleId, AccessRoleRequest request) {
        return Mono.defer(
                        () -> {
                            requireIdentifier(roleId);
                            AccessRoleRequest body = request == null ? empty() : request;
                            return repository
                                    .role(roleId)
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () ->
                                                            deny(
                                                                    actorId,
                                                                    ACTION_UPDATE,
                                                                    roleId,
                                                                    null,
                                                                    roleView(
                                                                            roleId,
                                                                            body.code(),
                                                                            body.name(),
                                                                            body.description(),
                                                                            body.parentId(),
                                                                            false,
                                                                            body.enabled()),
                                                                    REASON_ROLE_MISSING)))
                                    .flatMap(before -> update(actorId, before, body))
                                    .map(AccessRoleView::of);
                        })
                .as(transactions::transactional);
    }

    /**
     * Applies an update request to a loaded role. The denial checks run first (they are audited); the
     * validation and cycle check happen in {@link #applyUpdate}.
     */
    private Mono<AccessRole> update(String actorId, AccessRole before, AccessRoleRequest body) {
        if (body.code() != null
                && !body.code().isBlank()
                && !before.code().equals(body.code().trim())) {
            return deny(
                    actorId,
                    ACTION_UPDATE,
                    before.id(),
                    before,
                    roleView(
                            before.id(),
                            body.code(),
                            body.name(),
                            body.description(),
                            body.parentId(),
                            before.builtIn(),
                            body.enabled()),
                    REASON_IMMUTABLE_CODE);
        }
        boolean enabled = body.enabled() == null ? before.enabled() : body.enabled();
        if (before.builtIn() && Roles.ADMIN.equals(before.code()) && !enabled) {
            return deny(
                    actorId,
                    ACTION_DISABLE,
                    before.id(),
                    before,
                    roleView(
                            before.id(),
                            before.code(),
                            before.name(),
                            before.description(),
                            before.parentId(),
                            true,
                            false),
                    REASON_BUILT_IN_ADMIN);
        }
        return applyUpdate(actorId, before, body);
    }

    private Mono<AccessRole> applyUpdate(
            String actorId, AccessRole before, AccessRoleRequest body) {
        String name = body.name() == null ? before.name() : validateName(body.name());
        String description =
                body.description() == null
                        ? before.description()
                        : normalizeDescription(body.description());
        // null keeps the current parent; "" (or blank) clears it.
        String parentId = body.parentId() == null ? before.parentId() : blankToNull(body.parentId());
        boolean enabled = body.enabled() == null ? before.enabled() : body.enabled();
        String action = actionFor(before, enabled);
        AccessRole requested =
                roleView(
                        before.id(),
                        before.code(),
                        name,
                        description,
                        parentId,
                        before.builtIn(),
                        enabled);
        // Re-sending the current parent is a no-op; only a different one needs validating.
        Mono<Void> parentCheck =
                parentId == null || parentId.equals(before.parentId())
                        ? Mono.empty()
                        : validateParent(before, parentId);
        return parentCheck
                .onErrorResume(
                        Denial.class,
                        denial ->
                                deny(
                                                actorId,
                                                action,
                                                before.id(),
                                                before,
                                                requested,
                                                denial.getMessage())
                                        .then())
                .then(
                        Mono.defer(
                                () ->
                                        repository.updateRole(
                                                before.id(), name, description, parentId, enabled)))
                .then(repository.role(before.id()))
                .switchIfEmpty(Mono.error(new IllegalStateException("角色更新后丢失：" + before.id())))
                .flatMap(after -> audit(actorId, action, before, after));
    }

    // --- validation -------------------------------------------------------------------------

    private static String validateCode(String code) {
        String value = code == null ? null : code.trim();
        if (value == null || value.isEmpty()) {
            throw badRequest("角色编码不能为空");
        }
        if (!CODE_PATTERN.matcher(value).matches()) {
            throw badRequest("角色编码只能包含小写字母、数字、下划线和短横线，并且以字母开头（2-64 位）");
        }
        return value;
    }

    private static String validateName(String name) {
        String value = name == null ? null : name.trim();
        if (value == null || value.isEmpty()) {
            throw badRequest("角色名称不能为空");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw badRequest("角色名称不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        return value;
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String value = description.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_DESCRIPTION_LENGTH) {
            throw badRequest("角色描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        }
        return value;
    }

    /**
     * Rejects a path id outside the identifier alphabet ids are generated from (letters, digits,
     * {@code -} and {@code _}). Ids are never interpolated into SQL; this only rejects obviously
     * malformed input early.
     */
    private static void requireIdentifier(String roleId) {
        if (roleId == null || roleId.isBlank() || !ID_PATTERN.matcher(roleId).matches()) {
            throw badRequest("角色标识无效");
        }
    }

    /**
     * On create, a parent only has to exist and be a permitted parent; a new role cannot close a
     * cycle.
     *
     * @param parentId the requested parent, or {@code null}
     */
    private Mono<Void> requireUsableParent(String parentId) {
        if (parentId == null) {
            return Mono.empty();
        }
        return repository
                .role(parentId)
                .switchIfEmpty(Mono.error(new Denial(REASON_PARENT_MISSING, HttpStatus.NOT_FOUND)))
                .flatMap(AccessAdminService::permitParent);
    }

    /**
     * On update, the parent must exist, be a permitted parent, and linking the role under it must
     * keep the single-parent graph acyclic.
     *
     * <p>Walks up from the prospective parent, one lookup at a time, following the stored parent
     * chain. Reaching the role itself means the new link would close a cycle: its own id, any
     * descendant, or an ancestor that already leads back. A disabled parent is fine (it contributes
     * nothing at resolution time); a dangling or looping existing chain is refused rather than
     * extended, so the stored configuration stays acyclic.
     */
    private Mono<Void> validateParent(AccessRole role, String parentId) {
        if (parentId == null) {
            return Mono.empty();
        }
        if (parentId.equals(role.id())) {
            return Mono.error(new Denial(REASON_CYCLE, HttpStatus.CONFLICT));
        }
        return repository
                .role(parentId)
                .switchIfEmpty(Mono.error(new Denial(REASON_PARENT_MISSING, HttpStatus.NOT_FOUND)))
                .flatMap(AccessAdminService::permitParent)
                .then(climbToRoot(role, parentId, parentId, new HashSet<>()));
    }

    /**
     * Rejects the built-in administrator role as a parent. Inheriting it would hand
     * {@code access.bypass_data_scope} to any role its holder can attach, so a caller with {@code
     * access.manage} could grant themselves an uncontrolled scope; subset checks arrive in a later
     * task.
     *
     * @param parent the prospective parent
     * @return completion, or a denial
     */
    private static Mono<Void> permitParent(AccessRole parent) {
        if (parent.builtIn() && Roles.ADMIN.equals(parent.code())) {
            return Mono.error(new Denial(REASON_BUILT_IN_ADMIN_PARENT, HttpStatus.CONFLICT));
        }
        return Mono.empty();
    }

    /**
     * Walks the parent chain starting at {@code current}, failing on the role or on a visited node.
     * The prospective parent itself was already checked for existence and permission, so its row is
     * re-read here only to continue upward; a missing ancestor is treated as a broken chain.
     *
     * @param role the role being moved (its old parent edge is replaced, so it is not followed)
     * @param start the prospective parent id
     * @param current the node whose parent to follow
     * @param visited the ids already visited on this walk
     */
    private Mono<Void> climbToRoot(
            AccessRole role, String start, String current, Set<String> visited) {
        if (current == null) {
            return Mono.empty();
        }
        if (current.equals(role.id())) {
            return Mono.error(new Denial(REASON_CYCLE, HttpStatus.CONFLICT));
        }
        if (!visited.add(current)) {
            return Mono.error(new Denial(REASON_CYCLE, HttpStatus.CONFLICT));
        }
        if (current.equals(role.parentId())) {
            // The role is moving here: its previous parent edge is deleted by this update.
            return Mono.empty();
        }
        return repository
                .role(current)
                .switchIfEmpty(
                        Mono.error(new Denial(REASON_PARENT_MISSING, HttpStatus.NOT_FOUND)))
                .flatMap(found -> climbToRoot(role, start, found.parentId(), visited));
    }

    /**
     * A rejected request carrying the reason and the HTTP status to answer with. Carried through the
     * reactive chain so the enclosing transaction can write its {@code DENIED} audit row; the
     * controller maps it onto a response.
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

    /**
     * Maps a call that failed with an audited rejection onto the response status it carries.
     *
     * @param error the failure raised by {@link #create} or {@link #update}
     * @return the equivalent status exception, or {@code null} when the failure is not a rejection
     */
    public static ResponseStatusException toResponseStatusException(Throwable error) {
        return error instanceof Denial denial ? denial.toException() : null;
    }

    // --- audit ------------------------------------------------------------------------------

    /**
     * Appends the SUCCESS row for a completed mutation.
     *
     * <p>An audit-insert failure is <em>not</em> swallowed: it propagates so the enclosing
     * transaction rolls the mutation back. Callers only see a failed request, never a silently
     * unaudited change.
     */
    private Mono<AccessRole> audit(
            String actorId, String action, AccessRole before, AccessRole after) {
        return repository
                .record(
                        actorId,
                        action,
                        ENTITY_TYPE,
                        after.id(),
                        json(before),
                        json(after),
                        RESULT_SUCCESS,
                        null)
                .thenReturn(after);
    }

    /**
     * Appends a DENIED row for a rejected mutation, then fails with the matching HTTP status.
     *
     * <p>Runs in its own nested transaction so the row survives the outer mutation transaction
     * rolling back with the rejection.
     */
    private Mono<AccessRole> deny(
            String actorId,
            String action,
            String entityId,
            AccessRole before,
            AccessRole requested,
            String reason) {
        Denial denial = new Denial(reason, statusFor(reason));
        Mono<Void> record =
                repository
                        .record(
                                actorId,
                                action,
                                ENTITY_TYPE,
                                entityId,
                                json(before),
                                json(requested),
                                RESULT_DENIED,
                                truncate(reason))
                        .onErrorResume(
                                error -> {
                                    log.error(
                                            "Failed to append the DENIED audit row {}",
                                            action,
                                            error);
                                    return Mono.empty();
                                });
        // The audit row is part of the rejection: it commits, then the request fails. If the row
        // cannot be written the request still fails, so a rejection is never silently unrecorded.
        return record.as(newTransactions::transactional).then(Mono.error(denial));
    }

    private static HttpStatus statusFor(String reason) {
        return REASON_PARENT_MISSING.equals(reason) || REASON_ROLE_MISSING.equals(reason)
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
    }

    private static String actionFor(AccessRole before, boolean enabled) {
        return before.enabled() && !enabled ? ACTION_DISABLE : ACTION_UPDATE;
    }

    private String json(AccessRole role) {
        if (role == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(new AuditRole(role));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize the access-audit payload", ex);
        }
    }

    /**
     * The audited projection of a role: exactly the fields {@link AccessRoleView} exposes, and
     * nothing else (never secrets). Also used to describe a request that was rejected.
     */
    private record AuditRole(
            String id,
            String code,
            String name,
            String description,
            String parentId,
            boolean builtIn,
            boolean enabled) {

        private AuditRole(AccessRole role) {
            this(
                    role.id(),
                    role.code(),
                    role.name(),
                    role.description(),
                    role.parentId(),
                    role.builtIn(),
                    role.enabled());
        }
    }

    /** Builds the in-memory role a request asked for, for the DENIED audit row. */
    private static AccessRole roleView(
            String id,
            String code,
            String name,
            String description,
            String parentId,
            boolean builtIn,
            Boolean enabled) {
        return new AccessRole(
                id,
                code == null ? null : code.trim(),
                name == null ? null : name.trim(),
                description,
                parentId == null ? null : blankToNull(parentId),
                builtIn,
                enabled == null || enabled);
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

    private static AccessRoleRequest empty() {
        return new AccessRoleRequest(null, null, null, null, null);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
