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
