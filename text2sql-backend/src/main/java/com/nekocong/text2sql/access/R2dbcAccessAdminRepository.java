package com.nekocong.text2sql.access;

import java.util.List;
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
        // One INSERT ... SELECT per key keeps every value bound (no IN-list is built from client
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
}
