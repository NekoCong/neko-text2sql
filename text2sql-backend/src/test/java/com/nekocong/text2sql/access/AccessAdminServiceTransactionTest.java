package com.nekocong.text2sql.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.api.dto.AccessRoleRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The mutation and its audit row are one unit: when the audit insert fails, the role write must not
 * survive. Uses the real R2DBC transaction manager with a spying repository, so the rollback path is
 * exercised for real rather than mocked away.
 */
@SpringBootTest
class AccessAdminServiceTransactionTest {

    @Autowired
    private AccessAdminRepository repository;

    @Autowired
    private ReactiveTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseClient db;

    private AccessAdminService service;

    private String actorId;

    @BeforeEach
    void setUp() {
        service = new AccessAdminService(repository, transactionManager, objectMapper);
        actorId = "test-actor-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    void aFailedAuditInsertRollsTheCreateBack() {
        String code = code();
        AccessAdminRepository failing = failingAudit(repository);

        AccessAdminService failingService =
                new AccessAdminService(failing, transactionManager, objectMapper);

        assertThatThrownBy(
                        () ->
                                failingService
                                        .create(actorId, new AccessRoleRequest(code, "回滚", null, null, true))
                                        .block())
                .hasMessageContaining("audit unavailable");

        assertThat(roleCount(code)).as("no role may be committed without its audit row").isZero();
    }

    @Test
    void aFailedAuditInsertRollsTheUpdateBack() {
        String code = code();
        String id = service.create(actorId, new AccessRoleRequest(code, "原名", null, null, true)).block().id();

        AccessAdminRepository failing = failingAudit(repository);
        AccessAdminService failingService =
                new AccessAdminService(failing, transactionManager, objectMapper);

        assertThatThrownBy(
                        () ->
                                failingService
                                        .update(actorId, id, new AccessRoleRequest(null, "新名", null, null, null))
                                        .block())
                .isNotNull();

        assertThat(roleName(id)).as("the rename must be rolled back").isEqualTo("原名");
    }

    @Test
    void aSuccessfulCreateCommitsTheRoleAndItsAuditRowTogether() {
        String code = code();

        service.create(actorId, new AccessRoleRequest(code, "原子", null, null, true))
                .as(StepVerifier::create)
                .expectNextCount(1)
                .verifyComplete();

        assertThat(roleCount(code)).isEqualTo(1);
        assertThat(auditCount(actorId)).isEqualTo(1);
    }

    /**
     * A serializer that blows up while writing a DENIED payload must not turn the 409/404 into a 500
     * and must not drop the row: the rejection's status still reaches the caller and the row is
     * written with a {@code null} payload.
     */
    @Test
    void anUnserializableDeniedPayloadStillRecordsTheRowAndKeepsTheStatus() {
        ObjectMapper broken =
                new ObjectMapper() {
                    @Override
                    public String writeValueAsString(Object value)
                            throws com.fasterxml.jackson.core.JsonProcessingException {
                        throw new com.fasterxml.jackson.core.JsonProcessingException("boom") {};
                    }
                };
        AccessAdminService brokenService =
                new AccessAdminService(repository, transactionManager, broken);

        String unknown = UUID.randomUUID().toString();
        // Unknown role -> 404 denial whose payload cannot be serialized.
        assertThatThrownBy(
                        () ->
                                brokenService
                                        .update(
                                                actorId,
                                                unknown,
                                                new AccessRoleRequest(null, "x", null, null, null))
                                        .block())
                // The rejection stays a denial (the controller maps it), not a 500 from serialization.
                .satisfies(
                        error ->
                                assertThat(AccessAdminService.toResponseStatusException(error))
                                        .isNotNull()
                                        .extracting(
                                                e ->
                                                        ((org.springframework.web.server
                                                                        .ResponseStatusException)
                                                                e)
                                                                .getStatusCode()
                                                                .value())
                                        .isEqualTo(404));

        assertThat(auditCount(actorId))
                .as("the DENIED row is written even though its payload was null")
                .isEqualTo(1);
    }

    /** Wraps the repository so only {@code record} fails, after any mutation statement has run. */
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

    private long roleCount(String code) {
        Long count =
                db.sql("SELECT COUNT(*) AS n FROM access_role WHERE code = :code")
                        .bind("code", code)
                        .map((row, meta) -> row.get("n", Long.class))
                        .one()
                        .block();
        return count == null ? 0L : count;
    }

    private String roleName(String id) {
        return db.sql("SELECT name FROM access_role WHERE id = :id")
                .bind("id", id)
                .map((row, meta) -> row.get("name", String.class))
                .one()
                .block();
    }

    private long auditCount(String actor) {
        Long count =
                db.sql("SELECT COUNT(*) AS n FROM access_audit WHERE actor_id = :actor")
                        .bind("actor", actor)
                        .map((row, meta) -> row.get("n", Long.class))
                        .one()
                        .block();
        return count == null ? 0L : count;
    }

    private static String code() {
        return "tx" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
