package com.nekocong.text2sql.access.api;

import com.nekocong.text2sql.access.AccessAdminService;
import com.nekocong.text2sql.access.AuditQueryService;
import com.nekocong.text2sql.access.DataScopeService;
import com.nekocong.text2sql.access.RoleDatasourceService;
import com.nekocong.text2sql.access.RolePermissionService;
import com.nekocong.text2sql.access.UserAdminService;
import com.nekocong.text2sql.access.api.dto.AccessAuditPageView;
import com.nekocong.text2sql.access.api.dto.AccessPermissionView;
import com.nekocong.text2sql.access.api.dto.AccessRolePermissionsRequest;
import com.nekocong.text2sql.access.api.dto.AccessRolePermissionsView;
import com.nekocong.text2sql.access.api.dto.AccessRoleRequest;
import com.nekocong.text2sql.access.api.dto.AccessRoleView;
import com.nekocong.text2sql.access.api.dto.AccessUserCreateRequest;
import com.nekocong.text2sql.access.api.dto.AccessUserDisplayNameRequest;
import com.nekocong.text2sql.access.api.dto.AccessUserPage;
import com.nekocong.text2sql.access.api.dto.AccessUserPasswordRequest;
import com.nekocong.text2sql.access.api.dto.AccessUserRolesRequest;
import com.nekocong.text2sql.access.api.dto.AccessUserStatusRequest;
import com.nekocong.text2sql.access.api.dto.AccessUserView;
import com.nekocong.text2sql.access.api.dto.DataScopeRequest;
import com.nekocong.text2sql.access.api.dto.DataScopeView;
import com.nekocong.text2sql.access.api.dto.DatasourceMatrixView;
import com.nekocong.text2sql.access.api.dto.RoleDatasourcesRequest;
import com.nekocong.text2sql.access.api.dto.RoleDatasourcesView;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Admin API for the role catalog: list, create and update roles.
 *
 * <p>There is no delete: a role is disabled with {@code PUT {enabled:false}}. Access is enforced by
 * {@code SecurityConfig} ({@code /api/admin/**} requires the {@code access.manage} permission
 * resolved from the access-control tables), so an unauthorized caller never reaches these handlers.
 *
 * <p>Validation and conflict failures are answered here with {@code {"status": ..., "message": ...}}.
 * The handler is deliberately local to this controller: the application-wide advice is untouched, so
 * the error shape of the auth, conversation, model, datasource and knowledge endpoints does not
 * change.
 *
 * <p>The acting user id (the token subject) is passed to the service for the audit trail; without an
 * authenticated principal the request fails closed with {@code 401}.
 *
 * <p>The permission catalog and the role-permission matrix live next to the role routes but are
 * mapped on a separate base path ({@code /api/admin/access-control}), because {@code /permissions}
 * is not a role resource. Both are gated by the same {@code access.manage} route rule. The
 * data-scope routes ({@code /data-scopes}) share that base path and rule.
 */
@RestController
public class AccessControlController {

    private static final String ROLES = "/api/admin/access-control/roles";

    private static final String DATA_SCOPES = "/api/admin/access-control/data-scopes";

    private static final String USERS = "/api/admin/access-control/users";

    private static final String DATASOURCE_ACCESS = "/api/admin/access-control/datasource-access";

    private final AccessAdminService service;
    private final RolePermissionService permissions;
    private final DataScopeService dataScopes;
    private final UserAdminService users;
    private final RoleDatasourceService roleDatasources;
    private final AuditQueryService auditService;

    /**
     * Creates the controller.
     *
     * @param service the role administration service
     * @param permissions the permission catalog and role-permission matrix service
     * @param dataScopes the data-scope policy service
     * @param users the user lifecycle service
     * @param roleDatasources the role → datasource matrix service
     * @param auditService the read-only audit-trail service
     */
    public AccessControlController(
            AccessAdminService service,
            RolePermissionService permissions,
            DataScopeService dataScopes,
            UserAdminService users,
            RoleDatasourceService roleDatasources,
            AuditQueryService auditService) {
        this.service = service;
        this.permissions = permissions;
        this.dataScopes = dataScopes;
        this.users = users;
        this.roleDatasources = roleDatasources;
        this.auditService = auditService;
    }

    /**
     * Lists every role, ordered by code.
     *
     * @return the roles
     */
    @GetMapping(path = ROLES)
    public Mono<List<AccessRoleView>> list() {
        return service.list();
    }

    /**
     * Lists the access-control audit trail, newest first, paginated and filtered.
     *
     * <p>Read-only: there are no write/delete audit endpoints. An unknown filter value (for the
     * exact-match filters) simply matches nothing; only an invalid {@code result}, an inverted {@code
     * from}/{@code to} range, or bad pagination is a {@code 400} (rendered in the controller's {@code
     * {status,message}} shape).
     *
     * @param actorId the exact actor filter, or omitted for all
     * @param action the exact action filter, or omitted for all
     * @param entityType the exact entity-type filter, or omitted for all
     * @param entityId the exact entity-id filter, or omitted for all
     * @param result {@code SUCCESS}/{@code DENIED}, or omitted for both
     * @param from the inclusive lower time bound (epoch millis), or omitted
     * @param to the exclusive upper time bound (epoch millis), or omitted
     * @param page the zero-based page number (default 0)
     * @param size the page size (default 50; capped at the service maximum)
     * @return the page of entries with the total count
     */
    @GetMapping(path = "/api/admin/access-control/audit")
    public Mono<AccessAuditPageView> audit(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return auditService.list(actorId, action, entityType, entityId, result, from, to, page, size);
    }

    /**
     * Creates a role.
     *
     * @param request the role
     * @return the created role
     */
    @PostMapping(path = ROLES)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AccessRoleView> create(@RequestBody AccessRoleRequest request) {
        return actorId()
                .flatMap(actor -> service.create(actor, request))
                .onErrorMap(AccessControlController::asStatusException);
    }

    /**
     * Updates a role ({@code code} is immutable).
     *
     * <p>An omitted or {@code null} field keeps its current value; {@code "parentId": ""} clears the
     * parent.
     *
     * @param id the role id
     * @param request the new values
     * @return the updated role
     */
    @PutMapping(path = ROLES + "/{id}")
    public Mono<AccessRoleView> update(
            @PathVariable String id, @RequestBody(required = false) AccessRoleRequest request) {
        return actorId()
                .flatMap(actor -> service.update(actor, id, request))
                .onErrorMap(AccessControlController::asStatusException);
    }

    /**
     * Lists the permission catalog, ordered by module then key.
     *
     * @return the catalog
     */
    @GetMapping(path = "/api/admin/access-control/permissions")
    public Mono<List<AccessPermissionView>> permissions() {
        return permissions.permissions();
    }

    /**
     * Reads a role's own permission set.
     *
     * @param id the role id
     * @return the role's permission keys
     */
    @GetMapping(path = ROLES + "/{id}/permissions")
    public Mono<AccessRolePermissionsView> rolePermissions(@PathVariable String id) {
        return permissions.permissionsOf(id);
    }

    /**
     * Replaces a role's permission set with the complete requested set.
     *
     * <p>The body is <em>required</em>: a missing body, or a body without a {@code permissions}
     * array, is a {@code 400} rather than an empty set, so a malformed client cannot silently revoke
     * every grant. Only an explicit {@code {"permissions": []}} clears the role.
     *
     * @param id the role id
     * @param request the new set
     * @return the stored set
     */
    @PutMapping(path = ROLES + "/{id}/permissions")
    public Mono<AccessRolePermissionsView> replaceRolePermissions(
            @PathVariable String id,
            @RequestBody(required = false) AccessRolePermissionsRequest request) {
        return actorId()
                .flatMap(actor ->
                        permissions.replacePermissions(
                                actor, id, request == null ? null : request.permissions()));
    }

    /**
     * Reads the full role → datasource matrix: every role (ordered by code) with its sorted grant
     * set, and every registered datasource.
     *
     * @return the matrix
     */
    @GetMapping(path = DATASOURCE_ACCESS)
    public Mono<DatasourceMatrixView> datasourceMatrix() {
        return roleDatasources.matrix();
    }

    /**
     * Reads a role's own datasource grant set.
     *
     * @param id the role id
     * @return the role's datasource ids
     */
    @GetMapping(path = ROLES + "/{id}/datasources")
    public Mono<RoleDatasourcesView> roleDatasources(@PathVariable String id) {
        return roleDatasources.datasourcesOf(id);
    }

    /**
     * Replaces a role's datasource grant set with the complete requested set.
     *
     * <p>The body is <em>required</em>: a missing body, or a body without a {@code datasourceIds}
     * array, is a {@code 400} rather than an empty set. Only an explicit {@code {"datasourceIds": []}}
     * clears the role.
     *
     * @param id the role id
     * @param request the new set
     * @return the stored set
     */
    @PutMapping(path = ROLES + "/{id}/datasources")
    public Mono<RoleDatasourcesView> replaceRoleDatasources(
            @PathVariable String id,
            @RequestBody(required = false) RoleDatasourcesRequest request) {
        return actorId()
                .flatMap(actor ->
                        roleDatasources.replaceDatasources(
                                actor, id, request == null ? null : request.datasourceIds()));
    }

    /**
     * Lists a role's data-scope rules, ordered by datasource then table.
     *
     * @param roleId the role id (required; a missing one is a {@code 400})
     * @param datasourceId the datasource to filter by, or omitted for all
     * @return the rules
     */
    @GetMapping(path = DATA_SCOPES)
    public Mono<List<DataScopeView>> listDataScopes(
            @RequestParam(required = false) String roleId,
            @RequestParam(required = false) String datasourceId) {
        if (roleId == null || roleId.isBlank()) {
            // A missing required parameter is a client error; route it through the local handler so the
            // body keeps the {status,message} shape like every other failure here.
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色标识不能为空"));
        }
        return dataScopes.list(roleId, datasourceId);
    }

    /**
     * Inserts or updates the rule for a role, datasource and table.
     *
     * <p>There is no raw predicate: a {@code FILTER} rule names a validated column and one entry of
     * the closed principal-attribute catalog.
     *
     * @param request the rule
     * @return the stored rule
     */
    @PutMapping(path = DATA_SCOPES)
    public Mono<DataScopeView> upsertDataScope(
            @RequestBody(required = false) DataScopeRequest request) {
        if (request == null) {
            // An empty body must not fall through to Spring's error shape: the service answers it.
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空"));
        }
        return actorId().flatMap(actor -> dataScopes.upsert(actor, request));
    }

    /**
     * Deletes one data-scope rule.
     *
     * @param id the rule id
     * @return no content
     */
    @DeleteMapping(path = DATA_SCOPES + "/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteDataScope(@PathVariable String id) {
        return actorId().flatMap(actor -> dataScopes.delete(actor, id));
    }

    /**
     * Lists users, ordered by username, paginated.
     *
     * @param query the case-insensitive username/display-name filter, or omitted for all
     * @param enabled keep only enabled/disabled users, or omitted for both
     * @param roleId keep only users directly assigned that role, or omitted for all
     * @param page the zero-based page number (default 0)
     * @param size the page size (default 20; capped at the service maximum)
     * @return the page of users with the total count
     */
    @GetMapping(path = USERS)
    public Mono<AccessUserPage> listUsers(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String roleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return users.list(query, enabled, roleId, page, size);
    }

    /**
     * Reads one user.
     *
     * @param id the user id
     * @return the user
     */
    @GetMapping(path = USERS + "/{id}")
    public Mono<AccessUserView> getUser(@PathVariable String id) {
        return users.detail(id);
    }

    /**
     * Creates a user.
     *
     * @param request the user
     * @return the created user
     */
    @PostMapping(path = USERS)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AccessUserView> createUser(@RequestBody(required = false) AccessUserCreateRequest request) {
        return actorId()
                .flatMap(actor -> users.create(actor, request))
                .onErrorMap(AccessControlController::asStatusException);
    }

    /**
     * Edits a user's display name (the only mutable free-form field).
     *
     * @param id the user id
     * @param request the new display name ({@code null}/blank clears it)
     * @return the updated user
     */
    @PutMapping(path = USERS + "/{id}")
    public Mono<AccessUserView> updateUserDisplayName(
            @PathVariable String id,
            @RequestBody(required = false) AccessUserDisplayNameRequest request) {
        return actorId()
                .flatMap(actor -> users.updateDisplayName(actor, id, request))
                .onErrorMap(AccessControlController::asStatusException);
    }

    /**
     * Enables or disables a user.
     *
     * @param id the user id
     * @param request the new status
     * @return the updated user
     */
    @PutMapping(path = USERS + "/{id}/status")
    public Mono<AccessUserView> updateUserStatus(
            @PathVariable String id, @RequestBody(required = false) AccessUserStatusRequest request) {
        return actorId()
                .flatMap(actor -> users.updateStatus(actor, id, request))
                .onErrorMap(AccessControlController::asStatusException);
    }

    /**
     * Resets a user's password.
     *
     * @param id the user id
     * @param request the new password
     * @return no content
     */
    @PostMapping(path = USERS + "/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> resetPassword(
            @PathVariable String id, @RequestBody(required = false) AccessUserPasswordRequest request) {
        return actorId()
                .flatMap(actor -> users.resetPassword(actor, id, request))
                .onErrorMap(AccessControlController::asStatusException);
    }

    /**
     * Replaces a user's role assignments.
     *
     * @param id the user id
     * @param request the complete new set
     * @return the updated user
     */
    @PutMapping(path = USERS + "/{id}/roles")
    public Mono<AccessUserView> replaceUserRoles(
            @PathVariable String id, @RequestBody(required = false) AccessUserRolesRequest request) {
        return actorId()
                .flatMap(actor -> users.replaceRoles(actor, id, request))
                .onErrorMap(AccessControlController::asStatusException);
    }

    /** Converts the service's audited rejection into the status exception the handler renders. */
    private static Throwable asStatusException(Throwable error) {
        ResponseStatusException mapped = AccessAdminService.toResponseStatusException(error);
        if (mapped == null) {
            mapped = UserAdminService.toResponseStatusException(error);
        }
        return mapped == null ? error : mapped;
    }

    /**
     * Renders a validation or conflict failure raised by the role API.
     *
     * @param exception the failure
     * @return the mapped response
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException exception) {
        HttpStatus resolved = HttpStatus.resolve(exception.getStatusCode().value());
        if (resolved == null) {
            resolved = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String reason = exception.getReason();
        String message = reason == null || reason.isBlank() ? resolved.getReasonPhrase() : reason;
        return ResponseEntity.status(resolved)
                .body(Map.of("status", resolved.value(), "message", message));
    }

    /**
     * Reads the acting user id from the authenticated JWT principal.
     *
     * @return the user id, or an error when the principal is missing (fail closed)
     */
    private static Mono<String> actorId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(Objects::nonNull)
                .map(Authentication::getPrincipal)
                .filter(Jwt.class::isInstance)
                .map(Jwt.class::cast)
                .map(Jwt::getSubject)
                .filter(subject -> subject != null && !subject.isBlank())
                // The decoder has already verified the signature, expiry and issuer.
                .switchIfEmpty(
                        Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证")));
    }
}
