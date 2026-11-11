package com.nekocong.text2sql.access;

import com.nekocong.text2sql.security.Roles;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The shared anti-escalation component: answers "may this actor grant these effective permissions?"
 * for the permission matrix, the role parent edge and user role assignment.
 *
 * <p>The rule is the same everywhere: a <em>built-in administrator</em> may grant anything (the
 * bootstrap exception); any other actor may only grant permission sets they themselves hold. The
 * actor's own effective permissions are resolved with {@link AccessContextResolver} (which already
 * applies the single-parent inheritance walk), and a role's effective permissions are computed with
 * the resolver's {@code effectiveRoleIdsFor}/{@code effectivePermissionsForRoles} helpers — the walk
 * is never reimplemented here.
 *
 * <p>Every lookup fails closed: an unresolvable actor (no roles, unknown id, a configuration cycle)
 * resolves to an empty permission set, which denies anything they do not hold.
 */
@Service
public class AccessEscalationService {

    private final AccessContextResolver access;

    private final AccessAdminRepository repository;

    /**
     * Creates the service.
     *
     * @param access the access-context resolver, reused for the effective-permission walk
     * @param repository the admin repository, for role lookups
     */
    public AccessEscalationService(AccessContextResolver access, AccessAdminRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    /**
     * Resolves the actor's effective permission keys, failing closed to an empty set when the actor
     * cannot be resolved (no roles, unknown, or a broken inheritance configuration).
     *
     * @param actorId the acting user id
     * @return the effective permission keys
     */
    public Mono<Set<String>> actorPermissions(String actorId) {
        return access.effectivePermissions(actorId).onErrorReturn(Set.of());
    }

    /**
     * Reports whether the actor holds the built-in administrator role, directly or through
     * inheritance. Fails closed to {@code false}.
     *
     * @param actorId the acting user id
     * @return {@code true} when the built-in admin role is in the actor's effective role set
     */
    public Mono<Boolean> isBuiltInAdmin(String actorId) {
        return Mono.zip(access.effectiveRoleIdsOf(actorId), repository.roles())
                .map(
                        tuple -> {
                            Set<String> ids = new HashSet<>(tuple.getT1());
                            for (AccessRole role : tuple.getT2()) {
                                if (role.builtIn()
                                        && Roles.ADMIN.equals(role.code())
                                        && ids.contains(role.id())) {
                                    return Boolean.TRUE;
                                }
                            }
                            return Boolean.FALSE;
                        })
                .onErrorReturn(Boolean.FALSE);
    }

    /**
     * Reports whether the actor covers every required permission. A built-in administrator covers
     * anything; otherwise the required set must be a subset of the actor's effective permissions.
     *
     * @param actorId the acting user id
     * @param required the effective permission set that would be granted
     * @return {@code true} when the grant is allowed
     */
    public Mono<Boolean> actorCovers(String actorId, Set<String> required) {
        if (required == null || required.isEmpty()) {
            return Mono.just(Boolean.TRUE);
        }
        return isBuiltInAdmin(actorId)
                .flatMap(
                        admin ->
                                admin
                                        ? Mono.just(Boolean.TRUE)
                                        : actorPermissions(actorId)
                                                .map(permissions -> permissions.containsAll(required)));
    }

    /**
     * Returns the first requested key the actor does not hold, or empty. A built-in administrator is
     * never missing a key. Keys are inspected in sorted order so the reported key is stable.
     *
     * @param actorId the acting user id
     * @param requestedKeys the requested permission keys
     * @return the first missing key, or empty when the actor may grant every key
     */
    public Mono<Optional<String>> missingKey(String actorId, Set<String> requestedKeys) {
        if (requestedKeys == null || requestedKeys.isEmpty()) {
            return Mono.just(Optional.empty());
        }
        return isBuiltInAdmin(actorId)
                .flatMap(
                        admin -> {
                            if (admin) {
                                return Mono.just(Optional.<String>empty());
                            }
                            return actorPermissions(actorId)
                                    .map(
                                            permissions -> {
                                                for (String key :
                                                        requestedKeys.stream().sorted().toList()) {
                                                    if (!permissions.contains(key)) {
                                                        return Optional.of(key);
                                                    }
                                                }
                                                return Optional.<String>empty();
                                            });
                        });
    }

    /**
     * Computes one role's effective permission keys (its own grants plus its enabled ancestors'),
     * reusing the resolver's inheritance walk.
     *
     * @param roleId the role id
     * @return the effective permission keys
     */
    public Mono<Set<String>> effectivePermissionsOfRole(String roleId) {
        return repository.role(roleId).flatMap(role -> effectivePermissionsOfRoles(List.of(role)));
    }

    /**
     * Computes the effective permission keys of the given roles (each enabled role plus its enabled
     * ancestors), reusing the resolver's inheritance walk.
     *
     * @param roles the directly assigned roles
     * @return the union of effective permission keys
     */
    public Mono<Set<String>> effectivePermissionsOfRoles(Collection<AccessRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return Mono.just(Set.of());
        }
        return access.effectiveRoleIdsFor(roles).flatMap(access::effectivePermissionsForRoles);
    }
}
