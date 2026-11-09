package com.nekocong.text2sql.access.api;

import com.nekocong.text2sql.access.AccessAdminService;
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
import org.springframework.web.bind.annotation.RequestMapping;
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
 */
@RestController
@RequestMapping("/api/admin/access-control/roles")
public class AccessControlController {

    private final AccessAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the role administration service
     */
    public AccessControlController(AccessAdminService service) {
        this.service = service;
    }

    /**
     * Lists every role, ordered by code.
     *
     * @return the roles
     */
    @GetMapping
    public Mono<List<AccessRoleView>> list() {
        return service.list();
    }

    /**
     * Creates a role.
     *
     * @param request the role
     * @return the created role
     */
    @PostMapping
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
    @PutMapping("/{id}")
    public Mono<AccessRoleView> update(
            @PathVariable String id, @RequestBody(required = false) AccessRoleRequest request) {
        return actorId()
                .flatMap(actor -> service.update(actor, id, request))
                .onErrorMap(AccessControlController::asStatusException);
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
