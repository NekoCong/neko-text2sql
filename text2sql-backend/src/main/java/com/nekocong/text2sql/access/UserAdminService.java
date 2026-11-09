package com.nekocong.text2sql.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.api.dto.AccessUserCreateRequest;
import com.nekocong.text2sql.access.api.dto.AccessUserPasswordRequest;
import com.nekocong.text2sql.access.api.dto.AccessUserRolesRequest;
import com.nekocong.text2sql.access.api.dto.AccessUserStatusRequest;
import com.nekocong.text2sql.access.api.dto.AccessUserView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The user lifecycle admin API: list, create, enable/disable, reset password and assign roles, with
 * the last-administrator lockout protection and one audit row per mutation.
 *
 * <p>Kept separate from {@link AccessAdminService} (roles), {@link RolePermissionService}
 * (permission matrix) and {@link DataScopeService} (data scopes) so each stays focused. The
 * transaction and audit patterns are the same:
 *
 * <ul>
 *   <li>Every mutation runs in one reactive transaction: the {@code app_user} / {@code
 *       access_user_role} write and its {@code SUCCESS} audit row commit together or not at all.
 *   <li>A rejection decided against loaded state (unknown user, unknown role, duplicate username,
 *       the last-administrator rule) writes exactly one {@code DENIED} row in its own {@code
 *       REQUIRES_NEW} transaction, then fails with the mapped status.
 *   <li>Pure shape validation (a malformed username/password/id, a missing required field) fails
 *       with {@code 400} before anything is loaded and writes no row.
 * </ul>
 *
 * <p><strong>Token version.</strong> Disabling, enabling and password reset bump {@code
 * app_user.token_version} in the same statement as the row update, so every token issued before the
 * change is rejected on the next request. Role changes do <em>not</em> bump {@code token_version}:
 * permissions are resolved from the database on every request, so the new role set applies to the
 * next request without a new login.
 *
 * <p><strong>Lockout protection.</strong> Disabling a user, or replacing their roles, is rejected
 * with {@code 409} if that user currently holds {@code access.manage} (directly or through inherited
 * roles) and, after the change, no other <em>enabled</em> user would still hold it. "Administrator"
 * means "holds {@code access.manage}", never the legacy {@code app_user.role} column, and the
 * effective-permission check reuses {@link AccessContextResolver} so inheritance is never
 * reimplemented here. The acting user may be the subject; the rule still applies.
 */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    /** Matches {@code app_user.username VARCHAR(64)}: 3-64 chars of letters, digits, dot, dash, underscore. */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{3,64}$");

    /** The alphabet user ids are generated from (UUIDs). */
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** Matches {@code app_user.display_name VARCHAR(100)}. */
    private static final int MAX_DISPLAY_NAME_LENGTH = 100;

    /** Matches {@code access_audit.reason VARCHAR(512)}. */
    private static final int MAX_REASON_LENGTH = 512;

    private static final String ENTITY_TYPE = "USER";

    private static final String ACTION_CREATE = "user.create";
    private static final String ACTION_ENABLE = "user.enable";
    private static final String ACTION_DISABLE = "user.disable";
    private static final String ACTION_PASSWORD_RESET = "user.password_reset";
    private static final String ACTION_ROLES = "user.roles";

    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_DENIED = "DENIED";

    private static final String REASON_DUPLICATE_USERNAME = "用户名已存在";
    private static final String REASON_USER_MISSING = "用户不存在";
    private static final String REASON_ROLE_MISSING = "角色不存在：";
    private static final String REASON_LAST_ADMIN = "不能移除最后一个拥有 access.manage 的管理员";
    private static final String REASON_ROLE_REQUIRED = "至少需要一个角色";
    private static final String REASON_ROLES_BODY_MISSING = "roleIds 不能为空";
    private static final String REASON_ROLE_ID_BLANK = "角色标识不能为空";

    private final AccessAdminRepository repository;
    private final AccessContextResolver access;
    private final TransactionalOperator transactions;
    private final TransactionalOperator newTransactions;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service.
     *
     * @param repository the admin repository
     * @param access the access-context resolver, reused for the effective-permission lockout check
     * @param transactionManager the R2DBC reactive transaction manager
     * @param objectMapper the shared Jackson mapper, used for the audit JSON payloads
     * @param passwordEncoder the encoder used to hash new passwords
     */
    public UserAdminService(
            AccessAdminRepository repository,
            AccessContextResolver access,
            ReactiveTransactionManager transactionManager,
            ObjectMapper objectMapper,
            PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.access = access;
        this.transactions = TransactionalOperator.create(transactionManager);
        this.newTransactions =
                TransactionalOperator.create(
                        transactionManager,
                        new DefaultTransactionDefinition(
                                TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Lists users, ordered by username.
     *
     * <p>Reads are not audited. Missing filters mean "any"; the {@code query} filter matches
     * username/display name case-insensitively (contains).
     *
     * @param query the username/display-name filter, or {@code null}
     * @param enabled the enabled filter, or {@code null}
     * @param roleId the direct-role filter, or {@code null}
     * @return the matching users
     */
    public Mono<List<AccessUserView>> list(String query, Boolean enabled, String roleId) {
        return repository
                .users(query, enabled, roleId)
                .map(users -> users.stream().map(AccessUserView::of).toList());
    }

    /**
     * Creates a user.
     *
     * @param actorId the acting user id
     * @param request the request (required)
     * @return the created user
     */
    public Mono<AccessUserView> create(String actorId, AccessUserCreateRequest request) {
        return Mono.defer(
                        () -> {
                            AccessUserCreateRequest body = request == null ? emptyCreate() : request;
                            String username = validateUsername(body.username());
                            String password = validatePassword(body.password());
                            String displayName = normalizeDisplayName(body.displayName());
                            List<String> roleIds = normalizeRoleIds(body.roleIds(), true);
                            AuditUser requested =
                                    requestedUser(username, displayName, true, roleIds);
                            return resolveRoles(roleIds)
                                    .onErrorResume(
                                            Denial.class,
                                            denial ->
                                                    deny(
                                                                    actorId,
                                                                    ACTION_CREATE,
                                                                    null,
                                                                    null,
                                                                    requested,
                                                                    denial)
                                                            .then(Mono.<List<AccessRole>>empty()))
                                    .flatMap(
                                            roles ->
                                                    create(
                                                            actorId,
                                                            username,
                                                            password,
                                                            displayName,
                                                            roles));
                        })
                .as(transactions::transactional)
                .onErrorMap(Denial.class, Denial::toException)
                .map(AccessUserView::of);
    }

    /** Validates uniqueness against the loaded state, then inserts and audits. */
    private Mono<AccessUser> create(
            String actorId, String username, String password, String displayName, List<AccessRole> roles) {
        AuditUser requested = requestedUser(username, displayName, true, codesOf(roles));
        return repository
                .userByUsername(username)
                .flatMap(
                        existing ->
                                deny(
                                        actorId,
                                        ACTION_CREATE,
                                        existing.id(),
                                        null,
                                        requested,
                                        new Denial(REASON_DUPLICATE_USERNAME, HttpStatus.CONFLICT)))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        encode(password)
                                                .flatMap(
                                                        hash ->
                                                                repository.insertUser(
                                                                        username,
                                                                        hash,
                                                                        displayName,
                                                                        roleIdsOf(roles)))
                                                .flatMap(user -> audit(actorId, ACTION_CREATE, null, user))));
    }

    /**
     * Enables or disables a user.
     *
     * <p>A no-op (the flag is unchanged) still returns {@code 200} and writes no audit row.
     *
     * @param actorId the acting user id
     * @param userId the user id
     * @param request the request (required; {@code enabled} required)
     * @return the updated user
     */
    public Mono<AccessUserView> updateStatus(String actorId, String userId, AccessUserStatusRequest request) {
        return Mono.defer(
                        () -> {
                            requireIdentifier(userId);
                            boolean enabled = requireEnabled(request);
                            String action = actionForStatus(enabled);
                            return repository
                                    .user(userId)
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () ->
                                                            deny(
                                                                            actorId,
                                                                            action,
                                                                            userId,
                                                                            null,
                                                                            null,
                                                                            missing())
                                                                    .then(Mono.<AccessUser>empty())))
                                    .flatMap(before -> updateStatus(actorId, before, enabled, action));
                        })
                .as(transactions::transactional)
                .onErrorMap(Denial.class, Denial::toException)
                .map(AccessUserView::of);
    }

    private Mono<AccessUser> updateStatus(
            String actorId, AccessUser before, boolean enabled, String action) {
        if (before.enabled() == enabled) {
            // A no-op writes nothing: neither the row nor an audit entry.
            return Mono.just(before);
        }
        Mono<Void> lockout = enabled ? Mono.empty() : ensureNotLastAdmin(before.id());
        AccessUser requested = withEnabled(before, enabled);
        return lockout
                .onErrorResume(
                        Denial.class,
                        denial ->
                                deny(
                                                actorId,
                                                action,
                                                before.id(),
                                                auditUser(before),
                                                auditUser(requested),
                                                denial)
                                        .then())
                .then(Mono.defer(() -> repository.updateUserStatus(before.id(), enabled)))
                .then(repository.user(before.id()))
                .switchIfEmpty(Mono.error(new IllegalStateException("用户更新后丢失：" + before.id())))
                .flatMap(after -> audit(actorId, action, before, after));
    }

    /**
     * Resets a user's password.
     *
     * @param actorId the acting user id
     * @param userId the user id
     * @param request the request (required; {@code password} required)
     * @return completion (204)
     */
    public Mono<Void> resetPassword(String actorId, String userId, AccessUserPasswordRequest request) {
        return Mono.defer(
                        () -> {
                            requireIdentifier(userId);
                            String password = validatePassword(request == null ? null : request.password());
                            return repository
                                    .user(userId)
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () ->
                                                            deny(
                                                                            actorId,
                                                                            ACTION_PASSWORD_RESET,
                                                                            userId,
                                                                            null,
                                                                            null,
                                                                            missing())
                                                                    .then(Mono.<AccessUser>empty())))
                                    .flatMap(
                                            before ->
                                                    encode(password)
                                                            .flatMap(
                                                                    hash ->
                                                                            repository.updateUserPassword(
                                                                                            userId, hash)
                                                                                    .then(
                                                                                            auditPasswordReset(
                                                                                                    actorId,
                                                                                                    before))));
                        })
                .as(transactions::transactional)
                .onErrorMap(Denial.class, Denial::toException);
    }

    /**
     * Replaces a user's role assignments.
     *
     * <p>The whole set is replaced; only an explicit empty list clears every assignment. Role changes
     * do not bump {@code token_version}.
     *
     * @param actorId the acting user id
     * @param userId the user id
     * @param request the request (required; {@code roleIds} required, may be empty)
     * @return the updated user
     */
    public Mono<AccessUserView> replaceRoles(String actorId, String userId, AccessUserRolesRequest request) {
        return Mono.defer(
                        () -> {
                            requireIdentifier(userId);
                            List<String> roleIds =
                                    normalizeRoleIds(request == null ? null : request.roleIds(), false);
                            return repository
                                    .user(userId)
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () ->
                                                            deny(
                                                                            actorId,
                                                                            ACTION_ROLES,
                                                                            userId,
                                                                            null,
                                                                            null,
                                                                            missing())
                                                                    .then(Mono.<AccessUser>empty())))
                                    .flatMap(before -> replaceRoles(actorId, before, roleIds));
                        })
                .as(transactions::transactional)
                .onErrorMap(Denial.class, Denial::toException)
                .map(AccessUserView::of);
    }

    private Mono<AccessUser> replaceRoles(String actorId, AccessUser before, List<String> roleIds) {
        return resolveRoles(roleIds)
                .onErrorResume(
                        Denial.class,
                        denial ->
                                deny(
                                                actorId,
                                                ACTION_ROLES,
                                                before.id(),
                                                auditUser(before),
                                                requestedUser(
                                                        before.username(),
                                                        before.displayName(),
                                                        before.enabled(),
                                                        roleIds),
                                                denial)
                                        .then(Mono.<List<AccessRole>>empty()))
                .flatMap(
                        roles ->
                                ensureNotLastAdmin(before.id())
                                        .onErrorResume(
                                                Denial.class,
                                                denial ->
                                                        deny(
                                                                        actorId,
                                                                        ACTION_ROLES,
                                                                        before.id(),
                                                                        auditUser(before),
                                                                        auditUser(withRoles(before, roles)),
                                                                        denial)
                                                                .then())
                                        .then(
                                                Mono.defer(
                                                        () ->
                                                                repository.replaceUserRoles(
                                                                        before.id(), roleIdsOf(roles))))
                                        .then(repository.user(before.id()))
                                        .switchIfEmpty(
                                                Mono.error(
                                                        new IllegalStateException(
                                                                "用户更新后丢失：" + before.id())))
                                        .flatMap(after -> audit(actorId, ACTION_ROLES, before, after)));
    }

    // --- lockout protection ----------------------------------------------------------------

    /**
     * Rejects the change when the subject currently holds {@code access.manage} and no other enabled
     * user would still hold it afterwards.
     */
    private Mono<Void> ensureNotLastAdmin(String subjectId) {
        return access.effectivePermissions(subjectId)
                .flatMap(
                        permissions -> {
                            if (!permissions.contains(Permissions.ACCESS_MANAGE)) {
                                return Mono.empty();
                            }
                            return repository
                                    .enabledUserIds()
                                    .flatMap(
                                            ids -> {
                                                List<String> others =
                                                        ids.stream()
                                                                .filter(id -> !id.equals(subjectId))
                                                                .toList();
                                                return anyHoldsAccessManage(others)
                                                        .flatMap(
                                                                any ->
                                                                        any
                                                                                ? Mono.<Void>empty()
                                                                                : Mono.error(lastAdmin()));
                                            });
                        });
    }

    /** Reports whether any of the given users holds {@code access.manage} (effectively). */
    private Mono<Boolean> anyHoldsAccessManage(List<String> userIds) {
        return Flux.fromIterable(userIds)
                .concatMap(
                        id ->
                                access.effectivePermissions(id)
                                        .map(permissions -> permissions.contains(Permissions.ACCESS_MANAGE)))
                .any(Boolean::booleanValue);
    }

    // --- role resolution -------------------------------------------------------------------

    /**
     * Resolves the requested role ids to roles, rejecting an unknown id with {@code 404}.
     */
    private Mono<List<AccessRole>> resolveRoles(List<String> roleIds) {
        return repository.roles().map(all -> {
            Map<String, AccessRole> byId = new HashMap<>();
            for (AccessRole role : all) {
                byId.put(role.id(), role);
            }
            List<AccessRole> resolved = new ArrayList<>(roleIds.size());
            for (String roleId : roleIds) {
                AccessRole role = byId.get(roleId);
                if (role == null) {
                    throw new Denial(REASON_ROLE_MISSING + roleId, HttpStatus.NOT_FOUND);
                }
                resolved.add(role);
            }
            return resolved;
        });
    }

    // --- validation (400, not audited) -----------------------------------------------------

    private static String validateUsername(String username) {
        String value = username == null ? null : username.trim();
        if (value == null || value.isEmpty()) {
            throw badRequest("用户名不能为空");
        }
        if (!USERNAME_PATTERN.matcher(value).matches()) {
            throw badRequest("用户名只能包含字母、数字、点、下划线和短横线（3-64 位）");
        }
        return value;
    }

    private static String validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw badRequest("密码不能为空");
        }
        if (password.length() < 8 || password.length() > 72) {
            throw badRequest("密码长度必须为 8-72 个字符");
        }
        return password;
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        String value = displayName.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw badRequest("显示名称不能超过 " + MAX_DISPLAY_NAME_LENGTH + " 个字符");
        }
        return value;
    }

    private static List<String> normalizeRoleIds(List<String> roleIds, boolean requireNonEmpty) {
        if (roleIds == null) {
            throw badRequest(requireNonEmpty ? REASON_ROLE_REQUIRED : REASON_ROLES_BODY_MISSING);
        }
        if (requireNonEmpty && roleIds.isEmpty()) {
            throw badRequest(REASON_ROLE_REQUIRED);
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String raw : roleIds) {
            if (raw == null || raw.isBlank()) {
                throw badRequest(REASON_ROLE_ID_BLANK);
            }
            ids.add(raw.trim());
        }
        return List.copyOf(ids);
    }

    private static boolean requireEnabled(AccessUserStatusRequest request) {
        if (request == null || request.enabled() == null) {
            throw badRequest("enabled 不能为空");
        }
        return request.enabled();
    }

    private static void requireIdentifier(String userId) {
        if (userId == null || userId.isBlank() || !ID_PATTERN.matcher(userId).matches()) {
            throw badRequest("用户标识无效");
        }
    }

    // --- audit -----------------------------------------------------------------------------

    private Mono<AccessUser> audit(String actorId, String action, AccessUser before, AccessUser after) {
        return repository
                .record(
                        actorId,
                        action,
                        ENTITY_TYPE,
                        after.id(),
                        json(before == null ? null : auditUser(before)),
                        json(auditUser(after)),
                        RESULT_SUCCESS,
                        null)
                .thenReturn(after);
    }

    private Mono<Void> auditPasswordReset(String actorId, AccessUser before) {
        AuditPasswordReset after =
                new AuditPasswordReset(
                        before.username(),
                        before.displayName(),
                        before.enabled(),
                        roleCodesOf(before),
                        "changed");
        return repository.record(
                actorId,
                ACTION_PASSWORD_RESET,
                ENTITY_TYPE,
                before.id(),
                json(auditUser(before)),
                json(after),
                RESULT_SUCCESS,
                null);
    }

    /**
     * Appends a DENIED row for a rejected mutation, then fails with that rejection. Runs in its own
     * nested transaction so the row survives the outer transaction rolling back.
     */
    private Mono<AccessUser> deny(
            String actorId,
            String action,
            String entityId,
            AuditUser before,
            AuditUser after,
            Denial denial) {
        Mono<Void> record =
                repository
                        .record(
                                actorId,
                                action,
                                ENTITY_TYPE,
                                entityId,
                                jsonForDenial(before),
                                jsonForDenial(after),
                                RESULT_DENIED,
                                truncate(denial.getMessage()))
                        .onErrorResume(
                                error -> {
                                    log.error("Failed to append the DENIED audit row {}", action, error);
                                    return Mono.empty();
                                });
        return record.as(newTransactions::transactional).then(Mono.error(denial));
    }

    private String jsonForDenial(Object audit) {
        try {
            return json(audit);
        } catch (IllegalStateException ex) {
            log.warn("Failed to serialize the DENIED audit payload; storing null instead", ex);
            return null;
        }
    }

    private String json(Object audit) {
        if (audit == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(audit);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize the access-audit payload", ex);
        }
    }

    private static AuditUser auditUser(AccessUser user) {
        return new AuditUser(
                user.username(), user.displayName(), user.enabled(), roleCodesOf(user));
    }

    private static AuditUser requestedUser(
            String username, String displayName, boolean enabled, List<String> roleCodes) {
        List<String> codes =
                roleCodes == null
                        ? List.of()
                        : roleCodes.stream().filter(Objects::nonNull).distinct().sorted().toList();
        return new AuditUser(username, displayName, enabled, codes);
    }

    private static List<String> roleCodesOf(AccessUser user) {
        return user.roles().stream()
                .map(AccessRole::code)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> codesOf(List<AccessRole> roles) {
        return roles.stream().map(AccessRole::code).filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static List<String> roleIdsOf(List<AccessRole> roles) {
        return roles.stream().map(AccessRole::id).toList();
    }

    private static AccessUser withEnabled(AccessUser user, boolean enabled) {
        return new AccessUser(
                user.id(),
                user.username(),
                user.displayName(),
                enabled,
                user.createdAt(),
                user.roles());
    }

    private static AccessUser withRoles(AccessUser user, List<AccessRole> roles) {
        return new AccessUser(
                user.id(),
                user.username(),
                user.displayName(),
                user.enabled(),
                user.createdAt(),
                roles);
    }

    private static String actionForStatus(boolean enabled) {
        return enabled ? ACTION_ENABLE : ACTION_DISABLE;
    }

    private static Denial lastAdmin() {
        return new Denial(REASON_LAST_ADMIN, HttpStatus.CONFLICT);
    }

    private static Denial missing() {
        return new Denial(REASON_USER_MISSING, HttpStatus.NOT_FOUND);
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= MAX_REASON_LENGTH
                ? reason
                : reason.substring(0, MAX_REASON_LENGTH);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private Mono<String> encode(String raw) {
        return Mono.fromCallable(() -> passwordEncoder.encode(raw))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static AccessUserCreateRequest emptyCreate() {
        return new AccessUserCreateRequest(null, null, null, null);
    }

    /**
     * Maps a call that failed with an audited rejection onto the response status it carries.
     *
     * @param error the failure raised by a mutation
     * @return the equivalent status exception, or {@code null} when the failure is not a rejection
     */
    public static ResponseStatusException toResponseStatusException(Throwable error) {
        return error instanceof Denial denial ? denial.toException() : null;
    }

    /**
     * The audited projection of a user: exactly {@code username}, {@code displayName}, {@code
     * enabled} and {@code roleCodes} — never the password hash or raw password material.
     */
    private record AuditUser(
            String username, String displayName, boolean enabled, List<String> roleCodes) {}

    /**
     * The audited projection of a password reset: the user fields plus the literal {@code "changed"},
     * never the actual password.
     */
    private record AuditPasswordReset(
            String username,
            String displayName,
            boolean enabled,
            List<String> roleCodes,
            String password) {}

    /**
     * A rejected mutation, carrying the reason and the HTTP status. Carried through the reactive
     * chain so the enclosing transaction can write its {@code DENIED} audit row.
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
