package com.nekocong.text2sql.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The permission-matrix replacement and its audit row are one unit, and the anti-escalation seam is
 * really consulted.
 *
 * <p>Uses the real R2DBC transaction manager with a spying repository, so the rollback path is
 * exercised for real rather than mocked away.
 */
@SpringBootTest
class RolePermissionServiceTransactionTest {

    @Autowired
    private AccessAdminRepository repository;

    @Autowired
    private ReactiveTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseClient db;

    private String roleId;
    private String actorId;

    @BeforeEach
    void setUp() {
        roleId = createRole();
        actorId = "test-actor-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        service(repository, PermissionEscalationGuard.permitAll())
                .replacePermissions(actorId, roleId, List.of(Permissions.CHAT_USE))
                .block();
    }

    @Test
    void aFailedSuccessAuditInsertRollsTheSetReplacementBack() {
        RolePermissionService failing =
                service(failingAudit(repository), PermissionEscalationGuard.permitAll());

        assertThatThrownBy(
                        () ->
                                failing.replacePermissions(
                                                actorId, roleId, List.of(Permissions.USER_READ))
                                        .block())
                .hasMessageContaining("audit unavailable");

        assertThat(storedKeys())
                .as("the delete + inserts must not survive a failed audit insert")
                .containsExactly(Permissions.CHAT_USE);
        assertThat(auditCount("SUCCESS"))
                .as("only the seed replace's row is present")
                .isEqualTo(1);
    }

    @Test
    void aDenyingGuardAuditsTheDenialAndReturnsForbidden() {
        RolePermissionService guarded =
                service(
                        repository,
                        (actor, role, requested) -> Mono.just(Optional.of("不能授予未持有的权限")));

        guarded.replacePermissions(actorId, roleId, List.of(Permissions.USER_MANAGE))
                .as(StepVerifier::create)
                .expectErrorSatisfies(
                        error -> assertThat(denialStatus(error)).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        assertThat(storedKeys()).containsExactly(Permissions.CHAT_USE);
        assertThat(auditCount("DENIED")).isEqualTo(1);
        assertThat(auditReason("DENIED")).isEqualTo("不能授予未持有的权限");
        assertThat(auditCount("SUCCESS")).isEqualTo(1);
    }

    @Test
    void aPermissiveGuardAppliesTheChange() {
        service(repository, PermissionEscalationGuard.permitAll())
                .replacePermissions(actorId, roleId, List.of(Permissions.USER_READ))
                .as(StepVerifier::create)
                .expectNextMatches(view -> view.permissions().equals(List.of(Permissions.USER_READ)))
                .verifyComplete();

        assertThat(storedKeys()).containsExactly(Permissions.USER_READ);
        assertThat(auditCount("SUCCESS")).isEqualTo(2);
    }

    // --- helpers ----------------------------------------------------------------------------

    private RolePermissionService service(
            AccessAdminRepository repo, PermissionEscalationGuard guard) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("escalationGuard", guard);
        return new RolePermissionService(
                repo,
                transactionManager,
                objectMapper,
                beans.getBeanProvider(PermissionEscalationGuard.class),
                new AccessLockoutService(repo));
    }

    /** Wraps the repository so only {@code record} fails, after the mutation statements have run. */
    private static AccessAdminRepository failingAudit(AccessAdminRepository delegate) {
        return new AccessAdminRepository() {
            @Override
            public Mono<List<AccessRole>> roles() {
                return delegate.roles();
            }

            @Override
            public Mono<AccessRole> role(String id) {
                return delegate.role(id);
            }

            @Override
            public Mono<AccessRole> roleByCode(String code) {
                return delegate.roleByCode(code);
            }

            @Override
            public Mono<AccessRole> insertRole(
                    String code, String name, String description, String parentId, boolean enabled) {
                return delegate.insertRole(code, name, description, parentId, enabled);
            }

            @Override
            public Mono<Void> updateRole(
                    String id, String name, String description, String parentId, boolean enabled) {
                return delegate.updateRole(id, name, description, parentId, enabled);
            }

            @Override
            public Mono<List<AccessPermission>> permissions() {
                return delegate.permissions();
            }

            @Override
            public Mono<List<String>> permissionKeysOf(String roleId) {
                return delegate.permissionKeysOf(roleId);
            }

            @Override
            public Mono<Void> replaceRolePermissions(String roleId, List<String> permissionIds) {
                return delegate.replaceRolePermissions(roleId, permissionIds);
            }

            @Override
            public Mono<List<String>> datasourceIdsOf(String roleId) {
                return delegate.datasourceIdsOf(roleId);
            }

            @Override
            public Mono<List<RoleDatasource>> datasourceGrants() {
                return delegate.datasourceGrants();
            }

            @Override
            public Mono<Void> replaceRoleDatasources(String roleId, List<String> datasourceIds) {
                return delegate.replaceRoleDatasources(roleId, datasourceIds);
            }

            @Override
            public Mono<List<DataScopeRule>> dataScopesOf(String roleId, String datasourceId) {
                return delegate.dataScopesOf(roleId, datasourceId);
            }

            @Override
            public Mono<DataScopeRule> dataScope(
                    String roleId, String datasourceId, String tableName) {
                return delegate.dataScope(roleId, datasourceId, tableName);
            }

            @Override
            public Mono<DataScopeRule> dataScopeById(String id) {
                return delegate.dataScopeById(id);
            }

            @Override
            public Mono<DataScopeRule> upsertDataScope(
                    String roleId,
                    String datasourceId,
                    String tableName,
                    DataScopePolicy policy,
                    String columnName,
                    String principalAttribute) {
                return delegate.upsertDataScope(
                        roleId, datasourceId, tableName, policy, columnName, principalAttribute);
            }

            @Override
            public Mono<Long> deleteDataScope(String id) {
                return delegate.deleteDataScope(id);
            }

            @Override
            public Mono<List<AccessUser>> users(
                    String query, Boolean enabled, String roleId, int size, long offset) {
                return delegate.users(query, enabled, roleId, size, offset);
            }

            @Override
            public Mono<Long> countUsers(String query, Boolean enabled, String roleId) {
                return delegate.countUsers(query, enabled, roleId);
            }

            @Override
            public Mono<AccessUser> user(String id) {
                return delegate.user(id);
            }

            @Override
            public Mono<AccessUser> userByUsername(String username) {
                return delegate.userByUsername(username);
            }

            @Override
            public Mono<AccessUser> insertUser(
                    String username, String passwordHash, String displayName, List<String> roleIds) {
                return delegate.insertUser(username, passwordHash, displayName, roleIds);
            }

            @Override
            public Mono<Void> updateUserStatus(String id, boolean enabled) {
                return delegate.updateUserStatus(id, enabled);
            }

            @Override
            public Mono<Void> updateUserPassword(String id, String passwordHash) {
                return delegate.updateUserPassword(id, passwordHash);
            }

            @Override
            public Mono<Void> updateUserDisplayName(String id, String displayName) {
                return delegate.updateUserDisplayName(id, displayName);
            }

            @Override
            public Mono<Void> replaceUserRoles(String userId, List<String> roleIds) {
                return delegate.replaceUserRoles(userId, roleIds);
            }

            @Override
            public Mono<List<String>> enabledUserIds() {
                return delegate.enabledUserIds();
            }

            @Override
            public Mono<List<RolePermissionLink>> rolePermissionLinks() {
                return delegate.rolePermissionLinks();
            }

            @Override
            public Mono<List<UserRoleLink>> enabledUserRoles() {
                return delegate.enabledUserRoles();
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
                return Mono.error(new IllegalStateException("audit unavailable"));
            }
        };
    }

    private static HttpStatus denialStatus(Throwable error) {
        assertThat(error).isInstanceOf(ResponseStatusException.class);
        return HttpStatus.valueOf(((ResponseStatusException) error).getStatusCode().value());
    }

    private String createRole() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String id = "test-role-perm-tx-" + suffix;
        db.sql(
                        "INSERT INTO access_role (id, code, name, built_in, enabled, created_at,"
                                + " updated_at) VALUES (:id, :code, 'Tx', FALSE, TRUE, 1, 1)")
                .bind("id", id)
                .bind("code", "permtx" + suffix)
                .fetch()
                .rowsUpdated()
                .block();
        return id;
    }

    private List<String> storedKeys() {
        return db.sql(
                        "SELECT p.permission_key AS perm_key FROM access_role_permission rp"
                                + " JOIN access_permission p ON p.id = rp.permission_id"
                                + " WHERE rp.role_id = :roleId ORDER BY p.permission_key")
                .bind("roleId", roleId)
                .map((row, meta) -> row.get("perm_key", String.class))
                .all()
                .collectList()
                .block();
    }

    private long auditCount(String result) {
        Long count =
                db.sql(
                                "SELECT COUNT(*) AS n FROM access_audit"
                                        + " WHERE actor_id = :actorId AND action = :action"
                                        + " AND entity_id = :entityId AND result = :result")
                        .bind("actorId", actorId)
                        .bind("action", "role.permissions")
                        .bind("entityId", roleId)
                        .bind("result", result)
                        .map((row, meta) -> row.get("n", Long.class))
                        .one()
                        .block();
        return count == null ? 0L : count;
    }

    private String auditReason(String result) {
        return db.sql(
                        "SELECT reason FROM access_audit WHERE actor_id = :actorId"
                                + " AND action = :action AND entity_id = :entityId AND result = :result")
                .bind("actorId", actorId)
                .bind("action", "role.permissions")
                .bind("entityId", roleId)
                .bind("result", result)
                .map((row, meta) -> row.get("reason", String.class))
                .one()
                .block();
    }
}
