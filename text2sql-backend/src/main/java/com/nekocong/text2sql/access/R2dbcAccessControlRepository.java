package com.nekocong.text2sql.access;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * R2DBC implementation of {@link AccessControlRepository}.
 *
 * <p>Lookups by role use generated placeholders, never string concatenation of values. The tables
 * are small and indexed by primary/unique keys, so a few queries per request are acceptable.
 */
@Repository
public class R2dbcAccessControlRepository implements AccessControlRepository {

    private static final String ROLE_COLUMNS =
            "r.id, r.code, r.name, r.description, r.parent_id, r.built_in, r.enabled";

    private final DatabaseClient client;

    /**
     * Creates the repository.
     *
     * @param client the R2DBC database client
     */
    public R2dbcAccessControlRepository(DatabaseClient client) {
        this.client = client;
    }

    @Override
    public Mono<AccountStatus> account(String userId) {
        return client.sql(
                        "SELECT id, username, role, enabled, token_version FROM app_user WHERE id = :id")
                .bind("id", userId)
                .map((row, meta) -> new AccountStatus(
                        row.get("id", String.class),
                        row.get("username", String.class),
                        row.get("role", String.class),
                        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                        row.get("token_version", Integer.class) == null
                                ? 0
                                : row.get("token_version", Integer.class)))
                .one();
    }

    @Override
    public Mono<List<AccessRole>> rolesOf(String userId) {
        return client.sql("SELECT " + ROLE_COLUMNS
                        + " FROM access_role r"
                        + " JOIN access_user_role ur ON ur.role_id = r.id"
                        + " WHERE ur.user_id = :userId"
                        + " ORDER BY r.code")
                .bind("userId", userId)
                .map((row, meta) -> role(row))
                .all()
                .collectList();
    }

    @Override
    public Mono<List<AccessRole>> allRoles() {
        return client.sql("SELECT " + ROLE_COLUMNS + " FROM access_role r ORDER BY r.code")
                .map((row, meta) -> role(row))
                .all()
                .collectList();
    }

    @Override
    public Mono<Set<String>> permissionKeys(Collection<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        List<String> ids = List.copyOf(roleIds);
        String sql = "SELECT DISTINCT p.permission_key"
                + " FROM access_permission p"
                + " JOIN access_role_permission rp ON rp.permission_id = p.id"
                + " WHERE rp.role_id IN (" + placeholders("r", ids.size()) + ")";
        return bind(client.sql(sql), "r", ids)
                .map((row, meta) -> row.get("permission_key", String.class))
                .all()
                .collectList()
                .map(LinkedHashSet::new);
    }

    @Override
    public Mono<Set<String>> datasourceIds(Collection<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        List<String> ids = List.copyOf(roleIds);
        String sql = "SELECT DISTINCT datasource_id FROM access_role_datasource"
                + " WHERE role_id IN (" + placeholders("r", ids.size()) + ")";
        return bind(client.sql(sql), "r", ids)
                .map((row, meta) -> row.get("datasource_id", String.class))
                .all()
                .collectList()
                .map(LinkedHashSet::new);
    }

    @Override
    public Mono<List<DataScopeRule>> dataScopes(Collection<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Mono.just(List.of());
        }
        List<String> ids = List.copyOf(roleIds);
        String sql = "SELECT id, role_id, datasource_id, table_name, policy, column_name,"
                + " principal_attribute FROM access_data_scope"
                + " WHERE role_id IN (" + placeholders("r", ids.size()) + ")";
        return bind(client.sql(sql), "r", ids)
                .map((row, meta) -> new DataScopeRule(
                        row.get("id", String.class),
                        row.get("role_id", String.class),
                        row.get("datasource_id", String.class),
                        row.get("table_name", String.class),
                        parsePolicy(row.get("policy", String.class)),
                        row.get("column_name", String.class),
                        row.get("principal_attribute", String.class)))
                .all()
                .collectList();
    }

    @Override
    public Mono<PrincipalAttributes> principal(String userId, String username) {
        return client.sql("SELECT scope_type, scope_value FROM user_scope WHERE user_id = :userId")
                .bind("userId", userId)
                .map((row, meta) -> new String[] {
                    row.get("scope_type", String.class), row.get("scope_value", String.class)
                })
                .all()
                .collectList()
                .map(rows -> buildPrincipal(userId, username, rows));
    }

    @Override
    public Mono<Void> assignRoleByCode(String userId, String roleCode) {
        // Idempotent: a user who already holds the role keeps the existing row, and the statement
        // still updates one row so the "role exists" check below keeps working.
        return client.sql("INSERT INTO access_user_role (user_id, role_id, created_at)"
                        + " SELECT :userId, r.id, :createdAt FROM access_role r"
                        + " WHERE r.code = :code AND r.enabled = TRUE"
                        + " AND NOT EXISTS (SELECT 1 FROM access_user_role ur"
                        + " WHERE ur.user_id = :userId AND ur.role_id = r.id)")
                .bind("userId", userId)
                .bind("createdAt", System.currentTimeMillis())
                .bind("code", roleCode)
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> rows == null || rows == 0L
                        ? exists(userId, roleCode)
                        : Mono.empty());
    }

    @Override
    public Mono<Void> grantDatasourceByCode(String roleCode, String datasourceId) {
        // Idempotent: an existing grant keeps its row and the statement still completes.
        return client.sql("INSERT INTO access_role_datasource (role_id, datasource_id)"
                        + " SELECT r.id, :ds FROM access_role r"
                        + " WHERE r.code = :code AND r.enabled = TRUE"
                        + " AND NOT EXISTS (SELECT 1 FROM access_role_datasource d"
                        + " WHERE d.role_id = r.id AND d.datasource_id = :ds)")
                .bind("ds", datasourceId)
                .bind("code", roleCode)
                .then();
    }

    @Override
    public Mono<Void> revokeDatasourceByCode(String roleCode, String datasourceId) {
        return client.sql("DELETE FROM access_role_datasource"
                        + " WHERE datasource_id = :ds"
                        + " AND role_id IN (SELECT id FROM access_role WHERE code = :code)")
                .bind("ds", datasourceId)
                .bind("code", roleCode)
                .then();
    }

    /** Confirms an already-assigned role exists, so a repeat assignment is a no-op. */
    private Mono<Void> exists(String userId, String roleCode) {
        return client.sql("SELECT 1 AS present FROM access_user_role ur"
                        + " JOIN access_role r ON r.id = ur.role_id"
                        + " WHERE ur.user_id = :userId AND r.code = :code")
                .bind("userId", userId)
                .bind("code", roleCode)
                .map((row, meta) -> Boolean.TRUE)
                .one()
                .switchIfEmpty(Mono.error(new IllegalStateException("角色不存在：" + roleCode)))
                .then();
    }

    private static PrincipalAttributes buildPrincipal(
            String userId, String username, List<String[]> rows) {
        Set<Long> classIds = new LinkedHashSet<>();
        Long studentId = null;
        String studentNo = null;
        for (String[] row : rows) {
            String type = row[0] == null ? "" : row[0];
            String value = row[1];
            switch (type) {
                case "class_id" -> classIds.add(parseLong(value, "class_id"));
                case "student_id" -> studentId = singleLong(studentId, value, "student_id");
                case "student_no" -> studentNo = singleText(studentNo, value, "student_no");
                default -> {
                    // Unknown attribute kinds grant nothing.
                }
            }
        }
        return new PrincipalAttributes(userId, username, studentId, studentNo, classIds);
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

    private static DataScopePolicy parsePolicy(String value) {
        try {
            return DataScopePolicy.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            // Corrupt rows deny instead of widening access.
            return DataScopePolicy.DENY;
        }
    }

    private static Long parseLong(String value, String scopeType) {
        try {
            return Long.valueOf(value == null ? "" : value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("数据范围配置无效：" + scopeType + "=" + value);
        }
    }

    private static Long singleLong(Long current, String value, String scopeType) {
        Long parsed = parseLong(value, scopeType);
        if (current != null && !current.equals(parsed)) {
            throw new IllegalStateException("数据范围配置冲突：存在多个 " + scopeType);
        }
        return parsed;
    }

    private static String singleText(String current, String value, String scopeType) {
        if (current != null && !current.equals(value)) {
            throw new IllegalStateException("数据范围配置冲突：存在多个 " + scopeType);
        }
        return value;
    }

    private static String placeholders(String prefix, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(':').append(prefix).append(i);
        }
        return builder.toString();
    }

    private static DatabaseClient.GenericExecuteSpec bind(
            DatabaseClient.GenericExecuteSpec spec, String prefix, List<String> ids) {
        DatabaseClient.GenericExecuteSpec bound = spec;
        for (int i = 0; i < ids.size(); i++) {
            bound = bound.bind(prefix + i, ids.get(i));
        }
        return bound;
    }
}
