package com.nekocong.text2sql.access;

import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The production anti-escalation guard: an administrator may not grant a permission they do not
 * themselves hold, unless they are the built-in administrator (the bootstrap exception).
 *
 * <p>Consulted by {@link RolePermissionService} after the request has been validated against the
 * catalog and before the set is written. The actor's effective permissions (including inherited
 * roles) are resolved by {@link AccessEscalationService}; an unresolvable actor denies (fails
 * closed).
 */
@Component
public class DefaultPermissionEscalationGuard implements PermissionEscalationGuard {

    private static final String REASON_PREFIX = "不能授予自己未持有的权限：";

    private final AccessEscalationService escalation;

    /**
     * Creates the guard.
     *
     * @param escalation the shared escalation component
     */
    public DefaultPermissionEscalationGuard(AccessEscalationService escalation) {
        this.escalation = escalation;
    }

    @Override
    public Mono<Optional<String>> check(String actorId, String roleId, Set<String> requestedKeys) {
        return escalation
                .missingKey(actorId, requestedKeys)
                .map(missing -> missing.map(key -> REASON_PREFIX + key));
    }
}
