package com.nekocong.text2sql.access.api;

import com.nekocong.text2sql.access.AccessAdminService;
import com.nekocong.text2sql.access.RolePermissionService;
import com.nekocong.text2sql.access.api.dto.AccessPermissionView;
import com.nekocong.text2sql.access.api.dto.AccessRolePermissionsRequest;
import com.nekocong.text2sql.access.api.dto.AccessRolePermissionsView;
import com.nekocong.text2sql.access.api.dto.AccessRoleRequest;
import com.nekocong.text2sql.access.api.dto.AccessRoleView;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * is not a role resource. Both are gated by the same {@code access.manage} route rule.
 */
@RestController
public class AccessControlController {

    private static final String ROLES = "/api/admin/access-control/roles";

    private final AccessAdminService service;
    private final RolePermissionService permissions;

    /**
     * Creates the controller.
     *
     * @param service the role administration service
     * @param permissions the permission catalog and role-permission matrix service
     */
    public AccessControlController(
            AccessAdminService service, RolePermissionService permissions) {
        this.service = service;
        this.permissions = permissions;
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

    /** Converts the service's audited rejection into the status exception the handler renders. */
    private static Throwable asStatusException(Throwable error) {
        ResponseStatusException mapped = AccessAdminService.toResponseStatusException(error);
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
