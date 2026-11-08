package com.nekocong.text2sql.access;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * Read access to the configurable access-control tables.
 *
 * <p>All methods are non-blocking R2DBC operations. Empty role collections must resolve to empty
 * results without issuing SQL.
 */
public interface AccessControlRepository {

    /**
     * Loads the account row.
     *
     * @param userId the user id
     * @return the account, or empty
     */
    Mono<AccountStatus> account(String userId);

    /**
     * Loads the roles directly assigned to a user (enabled or not; the resolver filters).
     *
     * @param userId the user id
     * @return the direct roles
     */
    Mono<List<AccessRole>> rolesOf(String userId);

    /**
     * Loads every role, used for inheritance resolution.
     *
     * @return all roles
     */
    Mono<List<AccessRole>> allRoles();

    /**
     * Loads the union of permission keys for the given roles.
     *
     * @param roleIds the role ids
     * @return the permission keys
     */
    Mono<Set<String>> permissionKeys(Collection<String> roleIds);

    /**
     * Loads the union of datasource grants for the given roles.
     *
     * @param roleIds the role ids
     * @return the datasource ids
     */
    Mono<Set<String>> datasourceIds(Collection<String> roleIds);

    /**
     * Loads every data-scope rule for the given roles.
     *
     * @param roleIds the role ids
     * @return the rules
     */
    Mono<List<DataScopeRule>> dataScopes(Collection<String> roleIds);

    /**
     * Loads the principal attributes of a user.
     *
     * @param userId the user id
     * @param username the login name
     * @return the attributes
     */
    Mono<PrincipalAttributes> principal(String userId, String username);

    /**
     * Assigns the enabled role with the given code to a user, if not already assigned.
     *
     * @param userId the user id
     * @param roleCode the role code ({@code admin}, {@code student} or {@code counselor})
     * @return completion, or an error when no enabled role carries that code
     */
    Mono<Void> assignRoleByCode(String userId, String roleCode);

    /**
     * Grants the enabled role with the given code access to a datasource, if not already granted.
     *
     * @param roleCode the role code ({@code admin}, {@code student} or {@code counselor})
     * @param datasourceId the datasource id
     * @return completion, or an error when no enabled role carries that code
     */
    Mono<Void> grantDatasourceByCode(String roleCode, String datasourceId);

    /**
     * Revokes the role with the given code's access to a datasource.
     *
     * @param roleCode the role code ({@code admin}, {@code student} or {@code counselor})
     * @param datasourceId the datasource id
     * @return completion
     */
    Mono<Void> revokeDatasourceByCode(String roleCode, String datasourceId);
}
