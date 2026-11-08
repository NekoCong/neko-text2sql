package com.nekocong.text2sql.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Verifies the R2DBC access-control repository against the real schema (H2, MySQL mode).
 */
@SpringBootTest
class R2dbcAccessControlRepositoryTest {

    @Autowired
    private AccessControlRepository repository;

    @Autowired
    private DatabaseClient client;

    @Test
    void readsAccountRolesPermissionsGrantsScopesAndPrincipal() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String userId = "test-user-" + suffix;
        String roleId = "test-role-" + suffix;
        String permissionId = "test-perm-" + suffix;

        exec("INSERT INTO app_user (id, username, password_hash, display_name, role, enabled,"
                + " token_version, created_at) VALUES (:id, :username, 'x', 'T', 'student', TRUE, 3, 1)",
                "id", userId, "username", "u" + suffix);
        exec("INSERT INTO access_role (id, code, name, built_in, enabled, created_at, updated_at)"
                + " VALUES (:id, :code, 'Test', FALSE, TRUE, 1, 1)",
                "id", roleId, "code", "test" + suffix);
        exec("INSERT INTO access_user_role (user_id, role_id, created_at) VALUES (:userId, :roleId, 1)",
                "userId", userId, "roleId", roleId);
        exec("INSERT INTO access_permission (id, permission_key, module, action, built_in)"
                + " VALUES (:id, :key, 'chat', 'use', FALSE)",
                "id", permissionId, "key", "test.use" + suffix);
        exec("INSERT INTO access_role_permission (role_id, permission_id) VALUES (:roleId, :permId)",
                "roleId", roleId, "permId", permissionId);
        exec("INSERT INTO access_role_datasource (role_id, datasource_id) VALUES (:roleId, 'campus')",
                "roleId", roleId);
        exec("INSERT INTO access_data_scope (id, role_id, datasource_id, table_name, policy,"
                + " column_name, principal_attribute, created_at, updated_at)"
                + " VALUES (:id, :roleId, 'campus', 'score', 'FILTER', 'student_id', 'studentId', 1, 1)",
                "id", "test-scope-" + suffix, "roleId", roleId);
        exec("INSERT INTO user_scope (user_id, scope_type, scope_value) VALUES"
                + " (:userId, 'class_id', '1'), (:userId, 'student_id', '2'),"
                + " (:userId, 'student_no', '2023" + suffix + "')",
                "userId", userId);

        AccountStatus account = repository.account(userId).block();
        assertThat(account).isNotNull();
        assertThat(account.enabled()).isTrue();
        assertThat(account.tokenVersion()).isEqualTo(3);

        List<AccessRole> roles = repository.rolesOf(userId).block();
        assertThat(roles).extracting(AccessRole::id).containsExactly(roleId);

        assertThat(repository.permissionKeys(List.of(roleId)).block())
                .containsExactly("test.use" + suffix);
        assertThat(repository.datasourceIds(List.of(roleId)).block()).containsExactly("campus");

        List<DataScopeRule> scopes = repository.dataScopes(List.of(roleId)).block();
        assertThat(scopes).hasSize(1);
        assertThat(scopes.get(0).policy()).isEqualTo(DataScopePolicy.FILTER);
        assertThat(scopes.get(0).columnName()).isEqualTo("student_id");
        assertThat(scopes.get(0).principalAttribute()).isEqualTo("studentId");

        PrincipalAttributes principal = repository.principal(userId, "u" + suffix).block();
        assertThat(principal).isNotNull();
        assertThat(principal.classIds()).containsExactly(1L);
        assertThat(principal.studentId()).isEqualTo(2L);
        assertThat(principal.studentNo()).isEqualTo("2023" + suffix);
    }

    private void exec(String sql, String... bindings) {
        DatabaseClient.GenericExecuteSpec spec = client.sql(sql);
        for (int i = 0; i < bindings.length; i += 2) {
            spec = spec.bind(bindings[i], bindings[i + 1]);
        }
        spec.then().block();
    }
}
