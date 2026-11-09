package com.nekocong.text2sql.access;

import java.util.Optional;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * Seam for the anti-escalation rule of the permission-set endpoint.
 *
 * <p>Consulted by the permission-matrix service after the request has been validated against the
 * catalog and before the set is written. The guard answers whether the change may be applied:
 *
 * <ul>
 *   <li>{@code Mono.just(Optional.empty())} allows the change;
 *   <li>{@code Mono.just(Optional.of(reason))} denies it with that human-readable reason, which is
 *       returned to the caller as {@code 403} and recorded in the {@code DENIED} audit row;
 *   <li>an error denies the change too, but a guard should return a reason so the outcome is
 *       auditable: an error is <em>not</em> caught as a denial.
 * </ul>
 *
 * <p>The contract is data-only on purpose: no exception type of this package appears in the
 * signature, so an implementation in any module (or a future bean) can be written without access to
 * this class's internals. {@link #permitAll()} is the default while the rule itself ("an
 * administrator may not grant a permission they lack") is unimplemented.
 *
 * <p>The intended implementation resolves the actor's {@link EffectiveAccess} for {@code actorId}
 * and denies any requested key absent from it.
 */
@FunctionalInterface
public interface PermissionEscalationGuard {

    /**
     * The guard that permits every change (the anti-escalation rule is not implemented yet).
     *
     * @return the permissive guard
     */
    static PermissionEscalationGuard permitAll() {
        return (actorId, roleId, requestedKeys) -> Mono.just(Optional.empty());
    }

    /**
     * Decides whether a permission-set change may be applied.
     *
     * @param actorId the acting user id (the JWT subject)
     * @param roleId the role whose set is being replaced
     * @param requestedKeys the requested keys, already trimmed, de-duplicated and validated against
     *     the catalog
     * @return an empty {@link Optional} to allow the change, or the denial reason
     */
    Mono<Optional<String>> check(String actorId, String roleId, Set<String> requestedKeys);
}
