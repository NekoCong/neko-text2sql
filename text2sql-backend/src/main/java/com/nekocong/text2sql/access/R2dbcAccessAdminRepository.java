package com.nekocong.text2sql.access;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * R2DBC implementation of {@link AccessAdminRepository}.
 *
 * <p>Every value is bound as a placeholder; only static column lists are concatenated into the SQL.
 * Ids are app-generated UUIDs because R2DBC has no portable generated-key API.
 */
@Repository
public class R2dbcAccessAdminRepository implements AccessAdminRepository {

    private static final String ROLE_COLUMNS =
            "id, code, name, description, parent_id, built_in, enabled";

    private static final String USER_ROLE_COLUMNS =
            "r.id, r.code, r.name, r.description, r.parent_id, r.built_in, r.enabled";

    private static final String USER_COLUMNS =
            "u.id, u.username, u.display_name, u.enabled, u.created_at";

    private static final String DATA_SCOPE_COLUMNS =
            "id, role_id, datasource_id, table_name, policy, column_name, principal_attribute";

    private final DatabaseClient client;

    /**
     * Creates the repository.
     *
     * @param client the R2DBC database client
     */
    public R2dbcAccessAdminRepository(DatabaseClient client) {
        this.client = client;
    }

    @Override
    public Mono<List<AccessRole>> roles() {
        return client.sql("SELECT " + ROLE_COLUMNS + " FROM access_role ORDER BY code")
                .map((row, meta) -> role(row))
                .all()
                .collectList();
    }

    @Override
    public Mono<AccessRole> role(String id) {
        return client.sql("SELECT " + ROLE_COLUMNS + " FROM access_role WHERE id = :id")
                .bind("id", id)
                .map((row, meta) -> role(row))
                .one();
    }

    @Override
    public Mono<AccessRole> roleByCode(String code) {
        return client.sql("SELECT " + ROLE_COLUMNS + " FROM access_role WHERE code = :code")
                .bind("code", code)
                .map((row, meta) -> role(row))
                .one();
    }

    @Override
    public Mono<AccessRole> insertRole(
            String code, String name, String description, String parentId, boolean enabled) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        DatabaseClient.GenericExecuteSpec spec =
                client.sql("INSERT INTO access_role"
                                + " (id, code, name, description, parent_id, built_in, enabled,"
                                + " created_at, updated_at)"
                                + " VALUES (:id, :code, :name, :description, :parentId, FALSE, :enabled,"
                                + " :createdAt, :updatedAt)")
                        .bind("id", id)
                        .bind("code", code)
                        .bind("name", name)
                        .bind("enabled", enabled)
                        .bind("createdAt", now)
                        .bind("updatedAt", now);
        spec = bindNullable(spec, "description", description);
        spec = bindNullable(spec, "parentId", parentId);
        return spec.then()
                .thenReturn(new AccessRole(id, code, name, description, parentId, false, enabled));
    }

    @Override
    public Mono<Void> updateRole(
            String id, String name, String description, String parentId, boolean enabled) {
        DatabaseClient.GenericExecuteSpec spec =
                client.sql("UPDATE access_role"
                                + " SET name = :name, description = :description,"
                                + " parent_id = :parentId, enabled = :enabled, updated_at = :updatedAt"
                                + " WHERE id = :id")
                        .bind("id", id)
                        .bind("name", name)
                        .bind("enabled", enabled)
                        .bind("updatedAt", System.currentTimeMillis());
        spec = bindNullable(spec, "description", description);
        spec = bindNullable(spec, "parentId", parentId);
        return spec.then();
    }

    @Override
    public Mono<List<AccessPermission>> permissions() {
        return client.sql("SELECT id, permission_key, module, action, description, built_in"
                        + " FROM access_permission ORDER BY module, permission_key")
                .map((row, meta) -> new AccessPermission(
                        row.get("id", String.class),
                        row.get("permission_key", String.class),
                        row.get("module", String.class),
                        row.get("action", String.class),
                        row.get("description", String.class),
                        Boolean.TRUE.equals(row.get("built_in", Boolean.class))))
                .all()
                .collectList();
    }

    @Override
    public Mono<List<String>> permissionKeysOf(String roleId) {
        return client.sql("SELECT p.permission_key"
                        + " FROM access_permission p"
                        + " JOIN access_role_permission rp ON rp.permission_id = p.id"
                        + " WHERE rp.role_id = :roleId"
                        + " ORDER BY p.permission_key")
                .bind("roleId", roleId)
                .map((row, meta) -> row.get("permission_key", String.class))
                .all()
                .collectList();
    }

    @Override
    public Mono<Void> replaceRolePermissions(String roleId, List<String> permissionIds) {
        Mono<Void> delete =
                client.sql("DELETE FROM access_role_permission WHERE role_id = :roleId")
                        .bind("roleId", roleId)
                        .then();
        if (permissionIds == null || permissionIds.isEmpty()) {
            return delete;
        }
        // One INSERT ... VALUES per key keeps every value bound (no IN-list is built from client
        // input). Duplicates are already removed by the service, so the primary key cannot collide.
        Mono<Void> insert = Mono.empty();
        for (String permissionId : List.copyOf(permissionIds)) {
            insert =
                    insert.then(
                            client.sql("INSERT INTO access_role_permission (role_id, permission_id)"
                                            + " VALUES (:roleId, :permissionId)")
                                    .bind("roleId", roleId)
                                    .bind("permissionId", permissionId)
                                    .then());
        }
        return delete.then(insert);
    }

    @Override
    public Mono<List<DataScopeRule>> dataScopesOf(String roleId, String datasourceId) {
        if (datasourceId == null) {
            return client.sql("SELECT " + DATA_SCOPE_COLUMNS + " FROM access_data_scope"
                            + " WHERE role_id = :roleId"
                            + " ORDER BY datasource_id, table_name")
                    .bind("roleId", roleId)
                    .map((row, meta) -> dataScope(row))
                    .all()
                    .collectList();
        }
        return client.sql("SELECT " + DATA_SCOPE_COLUMNS + " FROM access_data_scope"
                        + " WHERE role_id = :roleId AND datasource_id = :datasourceId"
                        + " ORDER BY datasource_id, table_name")
                .bind("roleId", roleId)
                .bind("datasourceId", datasourceId)
                .map((row, meta) -> dataScope(row))
                .all()
                .collectList();
    }

    @Override
    public Mono<DataScopeRule> dataScope(String roleId, String datasourceId, String tableName) {
        return client.sql("SELECT " + DATA_SCOPE_COLUMNS + " FROM access_data_scope"
                        + " WHERE role_id = :roleId AND datasource_id = :datasourceId"
                        + " AND table_name = :tableName")
                .bind("roleId", roleId)
                .bind("datasourceId", datasourceId)
                .bind("tableName", tableName)
                .map((row, meta) -> dataScope(row))
                .one();
    }

    @Override
    public Mono<DataScopeRule> dataScopeById(String id) {
        return client.sql("SELECT " + DATA_SCOPE_COLUMNS + " FROM access_data_scope WHERE id = :id")
                .bind("id", id)
                .map((row, meta) -> dataScope(row))
                .one();
    }

    @Override
    public Mono<DataScopeRule> upsertDataScope(
            String roleId,
            String datasourceId,
            String tableName,
            DataScopePolicy policy,
            String columnName,
            String principalAttribute) {
        // The unique key drives the upsert, so an existing row keeps its id and created_at. A plain
        // UPDATE-then-INSERT would race two concurrent admins; the key resolves it in one statement.
        long now = System.currentTimeMillis();
        DatabaseClient.GenericExecuteSpec spec =
                client.sql("INSERT INTO access_data_scope"
                                + " (id, role_id, datasource_id, table_name, policy, column_name,"
                                + " principal_attribute, created_at, updated_at)"
                                + " VALUES (:id, :roleId, :datasourceId, :tableName, :policy,"
                                + " :columnName, :principalAttribute, :createdAt, :updatedAt)"
                                + " ON DUPLICATE KEY UPDATE policy = :policy,"
                                + " column_name = :columnName,"
                                + " principal_attribute = :principalAttribute, updated_at = :updatedAt")
                        .bind("id", UUID.randomUUID().toString())
                        .bind("roleId", roleId)
                        .bind("datasourceId", datasourceId)
                        .bind("tableName", tableName)
                        .bind("policy", policy.name())
                        .bind("createdAt", now)
                        .bind("updatedAt", now);
        spec = bindNullable(spec, "columnName", columnName);
        spec = bindNullable(spec, "principalAttribute", principalAttribute);
        return spec.then()
                .then(
                        Mono.defer(
                                () -> dataScope(roleId, datasourceId, tableName)))
                .switchIfEmpty(
                        Mono.error(new IllegalStateException("数据范围写入后丢失")));
    }

    @Override
    public Mono<Long> deleteDataScope(String id) {
        return client.sql("DELETE FROM access_data_scope WHERE id = :id")
                .bind("id", id)
                .fetch()
                .rowsUpdated();
    }

    @Override
    public Mono<List<AccessUser>> users(
            String query, Boolean enabled, String roleId, int size, long offset) {
        Where where = where(query, enabled, roleId);
        String sql = "SELECT " + USER_COLUMNS + " FROM app_user u" + where.sql
                + " ORDER BY u.username, u.id"
                + " LIMIT :size OFFSET :offset";
        DatabaseClient.GenericExecuteSpec spec = bindWhere(client.sql(sql), where);
        spec = spec.bind("size", size).bind("offset", offset);
        return spec.map((row, meta) -> baseUser(row)).all().collectList().flatMap(this::attachRoles);
    }

    @Override
    public Mono<Long> countUsers(String query, Boolean enabled, String roleId) {
        Where where = where(query, enabled, roleId);
        String sql = "SELECT COUNT(*) AS n FROM app_user u" + where.sql;
        return bindWhere(client.sql(sql), where)
                .map((row, meta) -> {
                    Long count = row.get("n", Long.class);
                    return count == null ? 0L : count;
                })
                .one();
    }

    @Override
    public Mono<AccessUser> user(String id) {
        return client.sql("SELECT " + USER_COLUMNS + " FROM app_user u WHERE u.id = :id")
                .bind("id", id)
                .map((row, meta) -> baseUser(row))
                .one()
                .flatMap(this::withRoles);
    }

    @Override
    public Mono<AccessUser> userByUsername(String username) {
        return client.sql("SELECT " + USER_COLUMNS + " FROM app_user u WHERE u.username = :username")
                .bind("username", username)
                .map((row, meta) -> baseUser(row))
                .one()
                .flatMap(this::withRoles);
    }

    @Override
    public Mono<AccessUser> insertUser(
            String username, String passwordHash, String displayName, List<String> roleIds) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        // role, enabled and token_version are left to the column defaults ('student' / TRUE / 0).
        DatabaseClient.GenericExecuteSpec spec =
                client.sql("INSERT INTO app_user"
                                + " (id, username, password_hash, display_name, created_at)"
                                + " VALUES (:id, :username, :passwordHash, :displayName, :createdAt)")
                        .bind("id", id)
                        .bind("username", username)
                        .bind("passwordHash", passwordHash)
                        .bind("createdAt", now);
        spec = bindNullable(spec, "displayName", displayName);
        return spec.then().then(assignRoles(id, roleIds)).then(user(id));
    }

    @Override
    public Mono<Void> updateUserStatus(String id, boolean enabled) {
        return client.sql(
                        "UPDATE app_user SET enabled = :enabled, token_version = token_version + 1"
                                + " WHERE id = :id")
                .bind("id", id)
                .bind("enabled", enabled)
                .then();
    }

    @Override
    public Mono<Void> updateUserPassword(String id, String passwordHash) {
        return client.sql(
                        "UPDATE app_user SET password_hash = :passwordHash,"
                                + " token_version = token_version + 1 WHERE id = :id")
                .bind("id", id)
                .bind("passwordHash", passwordHash)
                .then();
    }

    @Override
    public Mono<Void> updateUserDisplayName(String id, String displayName) {
        DatabaseClient.GenericExecuteSpec spec =
                client.sql("UPDATE app_user SET display_name = :displayName WHERE id = :id")
                        .bind("id", id);
        spec = bindNullable(spec, "displayName", displayName);
        return spec.then();
    }

    @Override
    public Mono<Void> replaceUserRoles(String userId, List<String> roleIds) {
        Mono<Void> delete =
                client.sql("DELETE FROM access_user_role WHERE user_id = :userId")
                        .bind("userId", userId)
                        .then();
        return delete.then(assignRoles(userId, roleIds));
    }

    @Override
    public Mono<List<String>> enabledUserIds() {
        return client.sql("SELECT id FROM app_user WHERE enabled = TRUE")
                .map((row, meta) -> row.get("id", String.class))
                .all()
                .collectList();
    }

    @Override
    public Mono<Void> record(
            String actorId,
            String action,
            String entityType,
            String entityId,
            String beforeJson,
            String afterJson,
            String result,
            String reason) {
        DatabaseClient.GenericExecuteSpec spec =
                client.sql("INSERT INTO access_audit"
                                + " (id, actor_id, action, entity_type, entity_id, before_json,"
                                + " after_json, result, reason, created_at)"
                                + " VALUES (:id, :actorId, :action, :entityType, :entityId,"
                                + " :beforeJson, :afterJson, :result, :reason, :createdAt)")
                        .bind("id", UUID.randomUUID().toString())
                        .bind("actorId", actorId)
                        .bind("action", action)
                        .bind("entityType", entityType)
                        .bind("result", result)
                        .bind("createdAt", System.currentTimeMillis());
        spec = bindNullable(spec, "entityId", entityId);
        spec = bindNullable(spec, "beforeJson", beforeJson);
        spec = bindNullable(spec, "afterJson", afterJson);
        spec = bindNullable(spec, "reason", reason);
        return spec.then();
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    /** Builds the shared user-list/count {@code WHERE} fragment and its bound values. */
    private static Where where(String query, Boolean enabled, String roleId) {
        boolean hasQuery = query != null && !query.isBlank();
        boolean hasEnabled = enabled != null;
        boolean hasRole = roleId != null && !roleId.isBlank();
        List<String> conditions = new ArrayList<>();
        if (hasQuery) {
            conditions.add("(LOWER(u.username) LIKE :query OR LOWER(u.display_name) LIKE :query)");
        }
        if (hasEnabled) {
            conditions.add("u.enabled = :enabled");
        }
        if (hasRole) {
            conditions.add(
                    "EXISTS (SELECT 1 FROM access_user_role ur"
                            + " WHERE ur.user_id = u.id AND ur.role_id = :roleId)");
        }
        String sql = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        return new Where(
                sql,
                hasQuery ? query.trim().toLowerCase(Locale.ROOT) : null,
                enabled,
                hasRole ? roleId.trim() : null);
    }

    /** Binds the {@link Where} values to a statement (skipping the absent filters). */
    private static DatabaseClient.GenericExecuteSpec bindWhere(
            DatabaseClient.GenericExecuteSpec spec, Where where) {
        DatabaseClient.GenericExecuteSpec bound = spec;
        if (where.query != null) {
            bound = bound.bind("query", "%" + where.query + "%");
        }
        if (where.enabled != null) {
            bound = bound.bind("enabled", where.enabled);
        }
        if (where.roleId != null) {
            bound = bound.bind("roleId", where.roleId);
        }
        return bound;
    }

    /** The user-list/count filter state: the SQL fragment plus the values to bind. */
    private record Where(String sql, String query, Boolean enabled, String roleId) {}

    /** The account columns of a user row, without its roles. */
    private static UserBase baseUser(io.r2dbc.spi.Row row) {
        return new UserBase(
                row.get("id", String.class),
                row.get("username", String.class),
                row.get("display_name", String.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                row.get("created_at", Long.class) == null ? 0L : row.get("created_at", Long.class));
    }

    /** Attaches directly assigned roles to a single user. */
    private Mono<AccessUser> withRoles(UserBase base) {
        return attachRoles(List.of(base)).map(list -> list.get(0));
    }

    /**
     * Attaches directly assigned roles to a list of users with one follow-up query, keyed by user id.
     * Roles are ordered by code for a stable view.
     */
    private Mono<List<AccessUser>> attachRoles(List<UserBase> users) {
        if (users.isEmpty()) {
            return Mono.just(List.of());
        }
        List<String> ids = users.stream().map(UserBase::id).toList();
        String sql = "SELECT ur.user_id, " + USER_ROLE_COLUMNS
                + " FROM access_role r"
                + " JOIN access_user_role ur ON ur.role_id = r.id"
                + " WHERE ur.user_id IN (" + placeholders(ids.size()) + ")"
                + " ORDER BY r.code";
        return bind(client.sql(sql), ids)
                .map((row, meta) -> new UserRole(row.get("user_id", String.class), role(row)))
                .all()
                .collectList()
                .map(rows -> {
                    Map<String, List<AccessRole>> byUser = new HashMap<>();
                    for (UserRole row : rows) {
                        byUser.computeIfAbsent(row.userId, key -> new ArrayList<>()).add(row.role);
                    }
                    return users.stream()
                            .map(base -> new AccessUser(
                                    base.id,
                                    base.username,
                                    base.displayName,
                                    base.enabled,
                                    base.createdAt,
                                    byUser.getOrDefault(base.id, List.of())))
                            .toList();
                });
    }

    /** Inserts the given role assignments (de-duplicated by the caller). */
    private Mono<Void> assignRoles(String userId, List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Mono.empty();
        }
        Mono<Void> insert = Mono.empty();
        for (String roleId : List.copyOf(roleIds)) {
            insert =
                    insert.then(
                            client.sql("INSERT INTO access_user_role (user_id, role_id, created_at)"
                                            + " VALUES (:userId, :roleId, :createdAt)")
                                    .bind("userId", userId)
                                    .bind("roleId", roleId)
                                    .bind("createdAt", System.currentTimeMillis())
                                    .then());
        }
        return insert;
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(":uid").append(i);
        }
        return builder.toString();
    }

    private static DatabaseClient.GenericExecuteSpec bind(
            DatabaseClient.GenericExecuteSpec spec, List<String> ids) {
        DatabaseClient.GenericExecuteSpec bound = spec;
        for (int i = 0; i < ids.size(); i++) {
            bound = bound.bind("uid" + i, ids.get(i));
        }
        return bound;
    }

    /** A user's account columns, before its roles are attached. */
    private record UserBase(
            String id, String username, String displayName, boolean enabled, long createdAt) {}

    /** One role assignment row from the follow-up join. */
    private record UserRole(String userId, AccessRole role) {}

    private static AccessRole role(io.r2dbc.spi.Row row) {
        return new AccessRole(
                row.get("id", String.class),
                row.get("code", String.class),
                row.get("name", String.class),
                row.get("description", String.class),
                row.get("parent_id", String.class),
                Boolean.TRUE.equals(row.get("built_in", Boolean.class)),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)));
    }

    /**
     * Maps a row to a rule. A stored policy outside the enum cannot be produced by this service, but
     * the column is free-form: an unknown value fails closed as {@code DENY} (the policy engine
     * would deny it anyway) instead of throwing on a read.
     */
    private static DataScopeRule dataScope(io.r2dbc.spi.Row row) {
        String policy = row.get("policy", String.class);
        return new DataScopeRule(
                row.get("id", String.class),
                row.get("role_id", String.class),
                row.get("datasource_id", String.class),
                row.get("table_name", String.class),
                parsePolicy(policy),
                row.get("column_name", String.class),
                row.get("principal_attribute", String.class));
    }

    private static DataScopePolicy parsePolicy(String policy) {
        if (policy == null) {
            return DataScopePolicy.DENY;
        }
        for (DataScopePolicy value : DataScopePolicy.values()) {
            if (value.name().equalsIgnoreCase(policy.trim())) {
                return value;
            }
        }
        return DataScopePolicy.DENY;
    }
}
