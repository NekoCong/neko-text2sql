package com.nekocong.text2sql.access;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The shared last-administrator lockout check, evaluated in memory.
 *
 * <p>A mutation that could remove the last <em>enabled</em> user holding {@code access.manage} is
 * rejected with {@code 409}. The check loads all roles, role-permission links and enabled users'
 * role links, applies the proposed change in memory, then recomputes each enabled user's effective
 * permission set with {@link AccessContextResolver#effectiveRoleIds} (the shared inheritance walk,
 * never reimplemented). The change is unsafe only when a holder existed before and none remains
 * after: an already locked-out system is not further blocked, so access can be re-established.
 *
 * <p>The evaluation is not strictly atomic with the mutation (the same residual TOCTOU as the 5.4
 * user lockout); the mutation itself stays transactional. All lookups are bound SQL.
 */
@Service
public class AccessLockoutService {

    private final AccessAdminRepository repository;

    /**
     * Creates the service.
     *
     * @param repository the admin repository, for the read-only snapshot queries
     */
    public AccessLockoutService(AccessAdminRepository repository) {
        this.repository = repository;
    }

    /**
     * Reports whether replacing one role's permission set would still leave an enabled {@code
     * access.manage} holder (or whether there was none to lose).
     *
     * @param roleId the role whose set is replaced
     * @param newKeys the complete new key set
     * @return {@code true} when the change may be applied
     */
    public Mono<Boolean> retainsAccessManageAfterPermissionReplace(String roleId, Set<String> newKeys) {
        return evaluate(snapshot -> snapshot.replacePermissionKeys(roleId, newKeys));
    }

    /**
     * Reports whether disabling one role would still leave an enabled {@code access.manage} holder.
     *
     * @param roleId the role to disable
     * @return {@code true} when the change may be applied
     */
    public Mono<Boolean> retainsAccessManageAfterDisable(String roleId) {
        return evaluate(snapshot -> snapshot.disableRole(roleId));
    }

    /**
     * Reports whether reparenting one role would still leave an enabled {@code access.manage}
     * holder.
     *
     * @param roleId the role being reparented
     * @param newParentId the prospective parent id, or {@code null} to clear the parent
     * @return {@code true} when the change may be applied
     */
    public Mono<Boolean> retainsAccessManageAfterReparent(String roleId, String newParentId) {
        return evaluate(snapshot -> snapshot.reparentRole(roleId, newParentId));
    }

    /** Loads the snapshot, evaluates the before state, applies the change, and compares. */
    private Mono<Boolean> evaluate(Consumer<Snapshot> mutation) {
        return snapshot()
                .map(
                        snapshot -> {
                            boolean before = snapshot.anyEnabledUserHoldsAccessManage();
                            mutation.accept(snapshot);
                            boolean after = snapshot.anyEnabledUserHoldsAccessManage();
                            return !before || after;
                        });
    }

    private Mono<Snapshot> snapshot() {
        return Mono.zip(
                        repository.roles(),
                        repository.rolePermissionLinks(),
                        repository.enabledUserRoles())
                .map(tuple -> new Snapshot(tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    /** The in-memory access-control graph, mutable so a proposed change can be applied. */
    private static final class Snapshot {

        private final Map<String, AccessRole> rolesById = new HashMap<>();

        private final Map<String, Set<String>> keysByRoleId = new HashMap<>();

        private final Map<String, Set<String>> rolesByUserId = new HashMap<>();

        Snapshot(
                List<AccessRole> roles,
                List<RolePermissionLink> permissionLinks,
                List<UserRoleLink> userRoles) {
            for (AccessRole role : roles) {
                rolesById.put(role.id(), role);
            }
            for (RolePermissionLink link : permissionLinks) {
                keysByRoleId
                        .computeIfAbsent(link.roleId(), key -> new HashSet<>())
                        .add(link.permissionKey());
            }
            for (UserRoleLink link : userRoles) {
                rolesByUserId
                        .computeIfAbsent(link.userId(), key -> new HashSet<>())
                        .add(link.roleId());
            }
        }

        boolean anyEnabledUserHoldsAccessManage() {
            for (Set<String> roleIds : rolesByUserId.values()) {
                if (effectivePermissionKeys(roleIds).contains(Permissions.ACCESS_MANAGE)) {
                    return true;
                }
            }
            return false;
        }

        private Set<String> effectivePermissionKeys(Set<String> directRoleIds) {
            List<AccessRole> direct =
                    directRoleIds.stream()
                            .map(rolesById::get)
                            .filter(Objects::nonNull)
                            .toList();
            List<String> effectiveIds =
                    AccessContextResolver.effectiveRoleIds(
                            direct, new ArrayList<>(rolesById.values()));
            Set<String> keys = new HashSet<>();
            for (String id : effectiveIds) {
                Set<String> roleKeys = keysByRoleId.get(id);
                if (roleKeys != null) {
                    keys.addAll(roleKeys);
                }
            }
            return keys;
        }

        void replacePermissionKeys(String roleId, Set<String> newKeys) {
            keysByRoleId.put(roleId, newKeys == null ? Set.of() : new HashSet<>(newKeys));
        }

        void disableRole(String roleId) {
            AccessRole role = rolesById.get(roleId);
            if (role != null && role.enabled()) {
                rolesById.put(
                        roleId,
                        new AccessRole(
                                role.id(),
                                role.code(),
                                role.name(),
                                role.description(),
                                role.parentId(),
                                role.builtIn(),
                                false));
            }
        }

        void reparentRole(String roleId, String newParentId) {
            AccessRole role = rolesById.get(roleId);
            if (role != null) {
                rolesById.put(
                        roleId,
                        new AccessRole(
                                role.id(),
                                role.code(),
                                role.name(),
                                role.description(),
                                newParentId,
                                role.builtIn(),
                                role.enabled()));
            }
        }
    }
}
