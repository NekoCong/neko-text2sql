package com.nekocong.text2sql.security;

import com.nekocong.text2sql.access.EffectiveAccess;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import reactor.core.publisher.Mono;

/**
 * Authorizes a request against one permission of the caller's configured effective access.
 *
 * <p>The decision is taken from the {@link EffectiveAccess} published on the exchange by {@code
 * AccessStatusWebFilter}, never from the token's {@code role} claim: a custom role may hold {@code
 * access.manage} without being named {@code admin}, and an account whose access role changed loses
 * the capability on its next request even if its token still carries the old claim.
 *
 * <p>Fails closed. A missing authentication, a missing effective access (the filter did not run, or
 * rejected the request) or a missing permission all deny the request.
 */
public class PermissionAuthorizationManager
        implements ReactiveAuthorizationManager<AuthorizationContext> {

    /** Exchange attribute under which {@code AccessStatusWebFilter} publishes the access. */
    private static final String ACCESS = EffectiveAccess.class.getName();

    private final String permission;

    /**
     * Creates the manager.
     *
     * @param permission the required permission key (see {@code Permissions})
     */
    public PermissionAuthorizationManager(String permission) {
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("permission key is required");
        }
        this.permission = permission;
    }

    /**
     * Creates a manager for one permission key.
     *
     * @param permission the required permission key
     * @return the manager
     */
    public static PermissionAuthorizationManager permission(String permission) {
        return new PermissionAuthorizationManager(permission);
    }

    @Override
    public Mono<AuthorizationResult> authorize(
            Mono<Authentication> authentication, AuthorizationContext context) {
        return authentication
                .filter(Authentication::isAuthenticated)
                .map(authenticated -> (AuthorizationResult) new AuthorizationDecision(holds(access(context))))
                .defaultIfEmpty(new AuthorizationDecision(false));
    }

    /**
     * Reads the effective access from the exchange attributes.
     *
     * @param context the authorization context
     * @return the access, or {@code null} when it was not published
     */
    private static EffectiveAccess access(AuthorizationContext context) {
        if (context == null || context.getExchange() == null) {
            return null;
        }
        Object attribute = context.getExchange().getAttributes().get(ACCESS);
        return attribute instanceof EffectiveAccess access ? access : null;
    }

    private boolean holds(EffectiveAccess access) {
        return access != null && access.hasPermission(permission);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + permission + "]";
    }
}
