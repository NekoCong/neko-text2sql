package com.nekocong.text2sql.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.api.dto.AccessRoleRequest;
import com.nekocong.text2sql.access.api.dto.AccessRoleView;
import com.nekocong.text2sql.security.Roles;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
 *   <li>{@code name} is required, at most {@value #MAX_NAME_LENGTH} characters.
 *   <li>{@code parentId} must reference an existing role and must not create a cycle. A disabled
 *       parent is allowed: it contributes nothing at resolution time.
 *   <li>Built-in roles keep their {@code code}; the built-in administrator role cannot be disabled.
 *       Other built-in roles may be renamed or disabled.
 * </ul>
 *
 * <p>Every mutation appends exactly one {@code access_audit} row, and every rejected mutation appends
 * a {@code DENIED} row carrying the reason. The application database has no transaction manager, so
 * the mutation and its audit row are two statements: the audit row is issued after the mutation and
 * a failed audit insert is logged rather than failing the request (the mutation may stand without
 * its audit row; the reverse cannot occur).
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
    private static final String REASON_CYCLE = "会形成循环继承";
    private static final String REASON_PARENT_MISSING = "父角色不存在";
    private static final String REASON_ROLE_MISSING = "角色不存在";

    private final AccessAdminRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service.
     *
     * @param repository the admin repository
     * @param objectMapper the shared Jackson mapper, used for the audit JSON payloads
     */
    public AccessAdminService(AccessAdminRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
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
                    AccessRoleRequest body =
                            request == null
                                    ? new AccessRoleRequest(null, null, null, null, null)
                                    : request;
                    String code = validateCode(body.code());
                    String name = validateName(body.name());
                    String description = normalizeDescription(body.description());
                    String parentId = blankToNull(body.parentId());
                    boolean enabled = body.enabled() == null || body.enabled();
                    return repository
                            .roleByCode(code)
                            .flatMap(
                                    existing ->
                                            deny(
                                                    actorId,
                                                    ACTION_CREATE,
                                                    existing.id(),
                                                    null,
                                                    Map.of("code", code),
                                                    REASON_DUPLICATE_CODE))
                            .switchIfEmpty(
                                    Mono.defer(
                                            () ->
                                                    requireParentExists(parentId)
                                                            .then(
                                                                    repository.insertRole(
                                                                            code,
                                                                            name,
                                                                            description,
                                                                            parentId,
                                                                            enabled))
                                                            .flatMap(
                                                                    role ->
                                                                            audit(
                                                                                    actorId,
                                                                                    ACTION_CREATE,
                                                                                    role.id(),
                                                                                    null,
                                                                                    roleMap(role)))));
                });
    }

    /**
     * Updates a role's mutable fields: name, description, parent and enabled.
     *
     * <p>The code may be omitted or repeated verbatim but never changed. {@code null} name,
     * description or enabled keeps the current value; {@code parentId} is taken literally, so
     * {@code null} clears the parent. Turning an enabled role off is audited as
     * {@code role.disable}.
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
                    AccessRoleRequest body =
                            request == null
                                    ? new AccessRoleRequest(null, null, null, null, null)
                                    : request;
                    return repository
                            .role(roleId)
                            .switchIfEmpty(Mono.error(notFound(REASON_ROLE_MISSING)))
                            .flatMap(before -> denyIfBad(actorId, before, body));
                });
    }

    /**
     * Applies an update request to a loaded role. The denial checks (immutable code, built-in admin)
     * run first because they are audited; the validation and cycle check happen in
     * {@link #applyUpdate}.
     */
    private Mono<AccessRoleView> denyIfBad(
            String actorId, AccessRole before, AccessRoleRequest body) {
        if (body.code() != null
                && !body.code().isBlank()
                && !before.code().equals(body.code().trim())) {
            return deny(
                    actorId,
                    ACTION_UPDATE,
                    before.id(),
                    roleMap(before),
                    Map.of("code", body.code()),
                    REASON_IMMUTABLE_CODE);
        }
        boolean enabled = body.enabled() == null ? before.enabled() : body.enabled();
        if (before.builtIn() && Roles.ADMIN.equals(before.code()) && !enabled) {
            return deny(
                    actorId,
                    ACTION_DISABLE,
                    before.id(),
                    roleMap(before),
                    Map.of("enabled", false),
                    REASON_BUILT_IN_ADMIN);
        }
        return applyUpdate(actorId, before, body);
    }

    private Mono<AccessRoleView> applyUpdate(
            String actorId, AccessRole before, AccessRoleRequest body) {
        String name = body.name() == null ? before.name() : validateName(body.name());
        String description =
                body.description() == null
                        ? before.description()
                        : normalizeDescription(body.description());
        String parentId = body.parentId() == null ? null : blankToNull(body.parentId());
        boolean enabled = body.enabled() == null ? before.enabled() : body.enabled();
        String action = actionFor(before, enabled);
        // The state this request would produce; only used for the DENIED audit row.
        Map<String, Object> requested = new HashMap<>(roleMap(before));
        requested.put("name", name);
        requested.put("description", description);
        requested.put("parentId", parentId);
        requested.put("enabled", enabled);
        return validateParent(before, parentId)
                .onErrorResume(
                        CycleDenied.class,
                        denial ->
                                deny(
                                                actorId,
                                                action,
                                                before.id(),
                                                roleMap(before),
                                                requested,
                                                denial.getMessage())
                                        .then())
                .then(
                        Mono.defer(
                                () ->
                                        repository.updateRole(
                                                before.id(), name, description, parentId, enabled)))
                .then(repository.role(before.id()))
                .flatMap(
                        after -> audit(actorId, action, after.id(), roleMap(before), roleMap(after)));
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
        return value.isEmpty() ? null : value;
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

    /** On create, a parent only has to exist; a new role cannot close a cycle. */
    private Mono<Void> requireParentExists(String parentId) {
        if (parentId == null) {
            return Mono.empty();
        }
        return repository
                .role(parentId)
                .switchIfEmpty(Mono.error(notFound(REASON_PARENT_MISSING)))
                .then();
    }

    /**
     * On update, the parent must exist and linking the role under it must keep the single-parent
     * graph acyclic.
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
            return Mono.error(new CycleDenied());
        }
        return climbToRoot(role.id(), role, parentId, parentId, new HashSet<>());
    }

    /**
     * Walks the parent chain starting at {@code current}, failing on the role or on a visited node.
     *
     * @param roleId the role being moved
     * @param role the role's current state (its old parent edge is replaced, so it is not followed)
     * @param start the prospective parent id, used in the not-found message
     * @param current the node whose parent to follow
     * @param visited the ids already visited on this walk
     */
    private Mono<Void> climbToRoot(
            String roleId, AccessRole role, String start, String current, Set<String> visited) {
        if (current == null) {
            return Mono.empty();
        }
        if (current.equals(roleId)) {
            return Mono.error(new CycleDenied());
        }
        if (!visited.add(current)) {
            return Mono.error(new CycleDenied());
        }
        if (current.equals(start)) {
            // The prospective parent must exist even though its chain was not entered yet.
            return repository
                    .role(current)
                    .switchIfEmpty(Mono.error(notFound(REASON_PARENT_MISSING)))
                    .flatMap(found -> climbToRoot(roleId, role, start, found.parentId(), visited));
        }
        if (current.equals(role.parentId())) {
            // The role is moving here: its previous parent edge is deleted by this update.
            return Mono.empty();
        }
        return repository
                .role(current)
                .switchIfEmpty(Mono.error(notFound(REASON_PARENT_MISSING)))
                .flatMap(found -> climbToRoot(roleId, role, start, found.parentId(), visited));
    }

    /** A rejected parent link that would close an inheritance cycle (audited as DENIED). */
    private static final class CycleDenied extends RuntimeException {

        private CycleDenied() {
            super(REASON_CYCLE, null, false, false);
        }
    }

    // --- audit ------------------------------------------------------------------------------

    /** Appends the SUCCESS row for a completed mutation and returns the resulting state. */
    private Mono<AccessRoleView> audit(
            String actorId,
            String action,
            String entityId,
            Map<String, Object> before,
            Map<String, Object> after) {
        return repository
                .record(
                        actorId,
                        action,
                        ENTITY_TYPE,
                        entityId,
                        json(before),
                        json(after),
                        RESULT_SUCCESS,
                        null)
                .onErrorResume(error -> auditFailure(action, entityId, error))
                .thenReturn(new AccessRoleView(
                        String.valueOf(after.get("id")),
                        String.valueOf(after.get("code")),
                        String.valueOf(after.get("name")),
                        (String) after.get("description"),
                        (String) after.get("parentId"),
                        Boolean.TRUE.equals(after.get("builtIn")),
                        Boolean.TRUE.equals(after.get("enabled"))));
    }

    /** Appends a DENIED row for a rejected mutation, then fails with the matching HTTP status. */
    private Mono<AccessRoleView> deny(
            String actorId,
            String action,
            String entityId,
            Map<String, Object> before,
            Map<String, Object> requested,
            String reason) {
        return repository
                .record(
                        actorId,
                        action,
                        ENTITY_TYPE,
                        entityId,
                        json(before),
                        json(requested),
                        RESULT_DENIED,
                        truncate(reason))
                .onErrorResume(error -> auditFailure(action, entityId, error))
                .then(Mono.error(conflict(reason)));
    }

    private static Mono<Void> auditFailure(String action, String entityId, Throwable error) {
        log.error(
                "Failed to append the access audit row {} for role {}; the mutation is not rolled"
                        + " back",
                action,
                entityId,
                error);
        return Mono.empty();
    }

    private static String actionFor(AccessRole before, boolean enabled) {
        return before.enabled() && !enabled ? ACTION_DISABLE : ACTION_UPDATE;
    }

    private String json(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize the access-audit payload", ex);
            return null;
        }
    }

    /**
     * The audited projection of a role: exactly the fields {@link AccessRoleView} exposes, and
     * nothing else (never secrets).
     */
    private static Map<String, Object> roleMap(AccessRole role) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", role.id());
        map.put("code", role.code());
        map.put("name", role.name());
        map.put("description", role.description());
        map.put("parentId", role.parentId());
        map.put("builtIn", role.builtIn());
        map.put("enabled", role.enabled());
        return map;
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

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
