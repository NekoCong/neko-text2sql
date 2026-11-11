package com.nekocong.text2sql.access;

import java.util.List;
import java.util.Set;

/**
 * The resolved access context for one request: roles, permissions, datasource grants, data-scope
 * rules and principal attributes. Immutable.
 *
 * @param userId the authenticated user id
 * @param roles the effective (enabled) roles, including inherited ones
 * @param permissions the union of permission keys
 * @param datasourceIds the effective datasource grants
 * @param dataScopes every data-scope rule contributed by the effective roles
 * @param principal the resolved principal attributes
 */
public record EffectiveAccess(
        String userId,
        List<AccessRole> roles,
        Set<String> permissions,
        Set<String> datasourceIds,
        List<DataScopeRule> dataScopes,
        PrincipalAttributes principal) {

    /** Normalizes the collections so the context is structurally immutable. */
    public EffectiveAccess {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        datasourceIds = datasourceIds == null ? Set.of() : Set.copyOf(datasourceIds);
        dataScopes = dataScopes == null ? List.of() : List.copyOf(dataScopes);
    }

    /**
     * Reports whether a permission is held.
     *
     * @param key the permission key
     * @return {@code true} when present
     */
    public boolean hasPermission(String key) {
        return key != null && permissions.contains(key);
    }

    /**
     * Reports whether the caller bypasses data-scope filtering.
     *
     * @return {@code true} when the bypass permission is held
     */
    public boolean bypassesDataScope() {
        return hasPermission(Permissions.BYPASS_DATA_SCOPE);
    }

    /** @return the effective role codes, for display and diagnostics */
    public List<String> roleCodes() {
        return roles.stream().map(AccessRole::code).toList();
    }
}
