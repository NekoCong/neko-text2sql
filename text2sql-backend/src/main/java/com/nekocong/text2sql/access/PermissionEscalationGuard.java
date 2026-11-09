package com.nekocong.text2sql.access;

import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Seam for the anti-escalation rule of the permission-set endpoint.
 *
 * <p>Consulted by {@link AccessAdminService#replacePermissions} after the request has been validated
 * against the catalog and the role's current set has been loaded, and before the set is written.
 * Returning an error (a {@link AccessAdminService.Denial}) rejects the change and writes its {@code
 * DENIED} audit row. The default is the permissive instance: the rule itself ("an administrator may
 * not grant a permission they do not hold") is not implemented yet.
 *
 * <p>The intended implementation resolves the actor's {@link EffectiveAccess} and rejects any
 * requested key absent from it. It receives the actor id rather than the context so it can resolve
 * lazily and stay usable outside a request (for example in tests).
 */
@FunctionalInterface
public interface PermissionEscalationGuard {

    /**
     * This instance, which permits every change (no anti-escalation rule yet).
     *
     * @return the permissive guard
     */
    static PermissionEscalationGuard permitAll() {
        return (actorId, role, before, after) -> Mono.empty();
    }

    /**
     * Decides whether a permission-set change may be applied.
     *
     * @param actorId the acting user id
     * @param role the role whose set is being replaced
     * @param before the role's current keys, sorted
     * @param after the requested keys, sorted and de-duplicated
     * @return completion when the change is allowed, or an error rejecting it
     */
    Mono<Void> check(String actorId, AccessRole role, List<String> before, List<String> after);
}
