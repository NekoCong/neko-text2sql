package com.nekocong.text2sql.access;

import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Write access to the configurable access-control tables used by the admin API: role CRUD and the
 * audit trail.
 *
 * <p>Kept separate from {@link AccessControlRepository}, which stays read-only for resolution.
 */
public interface AccessAdminRepository {

    /**
     * Lists every role, ordered by code.
     *
     * @return all roles
     */
    Mono<List<AccessRole>> roles();

    /**
     * Finds one role by id.
     *
     * @param id the role id
     * @return the role, or empty
     */
    Mono<AccessRole> role(String id);

    /**
     * Finds one role by code.
     *
     * @param code the role code
     * @return the role, or empty
     */
    Mono<AccessRole> roleByCode(String code);

    /**
     * Inserts a role with an app-generated id.
     *
     * @param code the code
     * @param name the display name
     * @param description the description, or {@code null}
     * @param parentId the parent role, or {@code null}
     * @param enabled whether the role participates in effective access
     * @return the inserted role
     */
    Mono<AccessRole> insertRole(
            String code, String name, String description, String parentId, boolean enabled);

    /**
     * Updates the mutable fields of a role ({@code code}, {@code built_in} and ids are untouched).
     *
     * @param id the role id
     * @param name the display name
     * @param description the description, or {@code null}
     * @param parentId the parent role, or {@code null}
     * @param enabled whether the role participates in effective access
     * @return completion
     */
    Mono<Void> updateRole(
            String id, String name, String description, String parentId, boolean enabled);

    /**
     * Lists the whole permission catalog, ordered by module then key.
     *
     * @return the catalog
     */
    Mono<List<AccessPermission>> permissions();

    /**
     * Loads the permission keys granted to one role directly, ordered by key.
     *
     * <p>Inherited permissions are not included: this is the role's own, editable set.
     *
     * @param roleId the role id
     * @return the granted keys (empty when the role holds none)
     */
    Mono<List<String>> permissionKeysOf(String roleId);

    /**
     * Replaces one role's permission set.
     *
     * <p>Deletes every grant of the role, then inserts the given ones. Both statements must run in
     * the caller's transaction so the set is replaced atomically.
     *
     * @param roleId the role id
     * @param permissionIds the catalog ids to grant (already validated against the catalog)
     * @return completion
     */
    Mono<Void> replaceRolePermissions(String roleId, List<String> permissionIds);

    /**
     * Lists one role's data-scope rules, ordered by datasource then table.
     *
     * @param roleId the role id
     * @param datasourceId the datasource to restrict the listing to, or {@code null} for all
     * @return the rules (empty when the role has none)
     */
    Mono<List<DataScopeRule>> dataScopesOf(String roleId, String datasourceId);

    /**
     * Looks one data-scope rule up by its unique key ({@code role_id}, {@code datasource_id},
     * {@code table_name}).
     *
     * @param roleId the role id
     * @param datasourceId the datasource id
     * @param tableName the table name
     * @return the rule, or empty
     */
    Mono<DataScopeRule> dataScope(String roleId, String datasourceId, String tableName);

    /**
     * Looks one data-scope rule up by id.
     *
     * @param id the rule id
     * @return the rule, or empty
     */
    Mono<DataScopeRule> dataScopeById(String id);

    /**
     * Inserts or updates the rule for ({@code role_id}, {@code datasource_id}, {@code table_name}).
     *
     * <p>An existing row keeps its id and creation time; only the policy, column and attribute
     * change. The unique key enforces one policy per role and table.
     *
     * @param roleId the role id
     * @param datasourceId the datasource id
     * @param tableName the table name
     * @param policy the policy type
     * @param columnName the filtered column, or {@code null}
     * @param principalAttribute the principal attribute code, or {@code null}
     * @return the stored rule
     */
    Mono<DataScopeRule> upsertDataScope(
            String roleId,
            String datasourceId,
            String tableName,
            DataScopePolicy policy,
            String columnName,
            String principalAttribute);

    /**
     * Deletes one data-scope rule by id.
     *
     * @param id the rule id
     * @return the number of rows deleted ({@code 0} when the id is unknown)
     */
    Mono<Long> deleteDataScope(String id);

    /**
     * Appends one audit row.
     *
     * @param actorId the acting user id
     * @param action the action key ({@code role.create}, {@code role.update}, {@code role.disable},
     *     {@code role.permissions}, {@code data_scope.upsert}, {@code data_scope.delete})
     * @param entityType the entity type ({@code ROLE}, {@code DATA_SCOPE})
     * @param entityId the affected role id, or {@code null}
     * @param beforeJson the serialized before state, or {@code null}
     * @param afterJson the serialized after state, or {@code null}
     * @param result {@code SUCCESS} or {@code DENIED}
     * @param reason the human-readable reason, or {@code null}
     * @return completion
     */
    Mono<Void> record(
            String actorId,
            String action,
            String entityType,
            String entityId,
            String beforeJson,
            String afterJson,
            String result,
            String reason);
}
