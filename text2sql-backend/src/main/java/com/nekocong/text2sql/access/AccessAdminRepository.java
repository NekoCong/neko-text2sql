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
     * Loads the datasource ids granted to one role directly, ordered by id.
     *
     * <p>Inherited grants are not included: this is the role's own, editable set.
     *
     * @param roleId the role id
     * @return the granted datasource ids (empty when the role holds none)
     */
    Mono<List<String>> datasourceIdsOf(String roleId);

    /**
     * Loads every role → datasource grant, for the full matrix.
     *
     * @return the grants (one row per {@code (role_id, datasource_id)} pair)
     */
    Mono<List<RoleDatasource>> datasourceGrants();

    /**
     * Replaces one role's datasource grant set.
     *
     * <p>Deletes every grant of the role, then inserts the given ones. Both statements must run in
     * the caller's transaction so the set is replaced atomically.
     *
     * @param roleId the role id
     * @param datasourceIds the datasource ids to grant (already validated against the registry)
     * @return completion
     */
    Mono<Void> replaceRoleDatasources(String roleId, List<String> datasourceIds);

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
     * Lists users, ordered by username then id, with each user's directly assigned roles attached.
     *
     * <p>Every filter is optional; a {@code null} filter means "any". The {@code query} matches
     * username and display name case-insensitively (contains); {@code roleId} keeps only users
     * directly assigned that role. The page is applied in SQL ({@code LIMIT}/{@code OFFSET}), never
     * in memory.
     *
     * @param query the case-insensitive username/display-name contains filter, or {@code null}
     * @param enabled keep only enabled/disabled users, or {@code null} for both
     * @param roleId keep only users directly holding this role, or {@code null}
     * @param size the page size (already validated, at least 1)
     * @param offset the zero-based offset of the page (already validated, at least 0)
     * @return the matching users, ordered by username then id
     */
    Mono<List<AccessUser>> users(
            String query, Boolean enabled, String roleId, int size, long offset);

    /**
     * Counts the users matching the given filters (the {@code total} of a page query).
     *
     * @param query the case-insensitive username/display-name contains filter, or {@code null}
     * @param enabled keep only enabled/disabled users, or {@code null} for both
     * @param roleId keep only users directly holding this role, or {@code null}
     * @return the matching user count
     */
    Mono<Long> countUsers(String query, Boolean enabled, String roleId);

    /**
     * Finds one user by id, with directly assigned roles.
     *
     * @param id the user id
     * @return the user, or empty
     */
    Mono<AccessUser> user(String id);

    /**
     * Finds one user by username, with directly assigned roles.
     *
     * @param username the username
     * @return the user, or empty
     */
    Mono<AccessUser> userByUsername(String username);

    /**
     * Inserts a user and its role assignments.
     *
     * <p>The legacy {@code role} column is left to its {@code 'student'} default and {@code enabled}
     * / {@code token_version} to their column defaults; the client cannot set them.
     *
     * @param username the unique username
     * @param passwordHash the BCrypt hash
     * @param displayName the display name, or {@code null}
     * @param roleIds the role ids to assign (already validated)
     * @return the inserted user with its roles
     */
    Mono<AccessUser> insertUser(
            String username, String passwordHash, String displayName, List<String> roleIds);

    /**
     * Updates a user's enabled flag and bumps {@code token_version} in the same statement, so every
     * token issued before the change is rejected.
     *
     * @param id the user id
     * @param enabled the new flag
     * @return completion
     */
    Mono<Void> updateUserStatus(String id, boolean enabled);

    /**
     * Replaces a user's password hash and bumps {@code token_version} in the same statement.
     *
     * @param id the user id
     * @param passwordHash the new BCrypt hash
     * @return completion
     */
    Mono<Void> updateUserPassword(String id, String passwordHash);

    /**
     * Updates a user's display name. This is the only mutable free-form field; it does not bump
     * {@code token_version} (display name is not authorization-relevant).
     *
     * @param id the user id
     * @param displayName the new display name, or {@code null} to clear
     * @return completion
     */
    Mono<Void> updateUserDisplayName(String id, String displayName);

    /**
     * Replaces one user's role assignments.
     *
     * <p>Deletes every assignment of the user, then inserts the given ones. Both must run in the
     * caller's transaction so the set is replaced atomically.
     *
     * @param userId the user id
     * @param roleIds the complete new set (already validated)
     * @return completion
     */
    Mono<Void> replaceUserRoles(String userId, List<String> roleIds);

    /**
     * Lists the ids of every enabled user, for the last-administrator lockout check.
     *
     * @return the enabled user ids
     */
    Mono<List<String>> enabledUserIds();

    /**
     * Loads every role → permission-key link, for the in-memory last-administrator lockout
     * evaluation. Bound SQL only; no client input is interpolated.
     *
     * @return the role-permission links
     */
    Mono<List<RolePermissionLink>> rolePermissionLinks();

    /**
     * Loads every direct role assignment of every enabled user, for the in-memory last-administrator
     * lockout evaluation. Bound SQL only; no client input is interpolated.
     *
     * @return the enabled users' role links
     */
    Mono<List<UserRoleLink>> enabledUserRoles();

    /**
     * Appends one audit row.
     *
     * @param actorId the acting user id
     * @param action the action key ({@code role.create}, {@code role.update}, {@code role.disable},
     *     {@code role.permissions}, {@code data_scope.upsert}, {@code data_scope.delete}, {@code
     *     user.create}, {@code user.enable}, {@code user.disable}, {@code user.password_reset}, {@code
     *     user.roles})
     * @param entityType the entity type ({@code ROLE}, {@code DATA_SCOPE}, {@code USER})
     * @param entityId the affected id, or {@code null}
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
