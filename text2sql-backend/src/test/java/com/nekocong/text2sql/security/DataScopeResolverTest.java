package com.nekocong.text2sql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies scope resolution against the real application schema, now database-driven: the scope is a
 * projection of the account's roles, permissions and data scopes, and a stale or missing token
 * version, an unknown role, a disabled account or a missing account all fail closed instead of
 * granting access.
 */
@SpringBootTest
class DataScopeResolverTest {

    @Autowired
    private DataScopeResolver resolver;

    @Autowired
    private DatabaseClient client;

    @Test
    void studentResolvesToItsOwnRowAndClass() {
        String userId = account("student");
        scope(userId, "class_id", "7");
        scope(userId, "student_id", "70");
        scope(userId, "student_no", "2023070");

        DataScope scope = resolver.resolve(userId, "student", 0).block();

        assertThat(scope).isNotNull();
        assertThat(scope.isStudent()).isTrue();
        assertThat(scope.classIds()).containsExactly(7L);
        assertThat(scope.studentId()).isEqualTo(70L);
        assertThat(scope.studentNo()).isEqualTo("2023070");
        assertThat(scope.allowedDatasources()).contains("campus");
        assertThat(scope.access()).isNotNull();
        assertThat(scope.bypassesDataScope()).isFalse();
    }

    @Test
    void counselorResolvesToEveryManagedClass() {
        String userId = account("counselor");
        scope(userId, "class_id", "8");
        scope(userId, "class_id", "9");

        DataScope scope = resolver.resolve(userId, "counselor", 0).block();

        assertThat(scope).isNotNull();
        assertThat(scope.isCounselor()).isTrue();
        assertThat(scope.classIds()).containsExactlyInAnyOrder(8L, 9L);
        assertThat(scope.studentId()).isNull();
        assertThat(scope.studentNo()).isNull();
    }

    @Test
    void adminResolvesToABypassScope() {
        String userId = account("admin");

        DataScope scope = resolver.resolve(userId, "admin", 0).block();

        assertThat(scope).isNotNull();
        assertThat(scope.isAdmin()).isTrue();
        assertThat(scope.bypassesDataScope()).isTrue();
        assertThat(scope.classIds()).isEmpty();
        assertThat(scope.studentId()).isNull();
    }

    @Test
    void theScopeFollowsTheAssignedRoleNotTheTokenClaim() {
        // The token says "student", the account holds only the counselor role: the data wins.
        String userId = "scope-test-swapped-" + UUID.randomUUID();
        insertAccount(userId, "student", true, 0);
        assign(userId, "counselor");
        scope(userId, "class_id", "5");

        DataScope scope = resolver.resolve(userId, "student", 0).block();

        assertThat(scope).isNotNull();
        assertThat(scope.role()).isEqualTo("counselor");
        assertThat(scope.classIds()).containsExactly(5L);
    }

    @Test
    void aLegacyRoleNameIsRejected() {
        String userId = account("student");

        // The deprecated "user" role is not recognized: it fails closed instead of scoping.
        assertDenied(resolver.resolve(userId, "user", 0));
        assertDenied(resolver.resolve(userId, "teacher", 0));
    }

    @Test
    void aMissingTokenVersionFailsClosed() {
        String userId = account("student");
        scope(userId, "class_id", "1");
        scope(userId, "student_id", "1");
        scope(userId, "student_no", "2023001");

        assertThatThrownBy(() -> resolver.resolve(userId, "student", null).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> resolver.resolve(userId, "student").block())
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aStaleTokenVersionIsRejected() {
        String userId = account("student");

        assertThatThrownBy(() -> resolver.resolve(userId, "student", 99).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void aMissingAccountIsRejected() {
        String userId = "scope-test-missing-" + UUID.randomUUID();

        assertDenied(resolver.resolve(userId, "student", 0));
    }

    @Test
    void aDisabledAccountIsRejected() {
        String userId = "scope-test-disabled-" + UUID.randomUUID();
        insertAccount(userId, "student", false, 0);
        assign(userId, "student");

        assertThatThrownBy(() -> resolver.resolve(userId, "student", 0).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void anAccountWithoutRolesIsRejected() {
        String userId = "scope-test-norole-" + UUID.randomUUID();
        insertAccount(userId, "student", true, 0);

        assertDenied(resolver.resolve(userId, "student", 0));
    }

    @AfterEach
    void cleanUp() {
        client.sql("DELETE FROM user_scope WHERE user_id LIKE 'scope-test-%'").then().block();
        client.sql("DELETE FROM access_user_role WHERE user_id LIKE 'scope-test-%'").then().block();
        client.sql("DELETE FROM app_user WHERE id LIKE 'scope-test-%'").then().block();
    }

    private void assertDenied(reactor.core.publisher.Mono<DataScope> result) {
        assertThatThrownBy(result::block).isInstanceOf(ResponseStatusException.class);
    }

    /** Inserts an enabled account with a single role and returns its id. */
    private String account(String role) {
        String userId = "scope-test-" + role + "-" + UUID.randomUUID();
        insertAccount(userId, role, true, 0);
        assign(userId, role);
        return userId;
    }

    private void insertAccount(String userId, String role, boolean enabled, int tokenVersion) {
        client.sql("DELETE FROM app_user WHERE id = :id")
                .bind("id", userId)
                .then()
                .block();
        client.sql("INSERT INTO app_user (id, username, password_hash, display_name, role, enabled,"
                        + " token_version, created_at) VALUES (:id, :username, 'x', 'Test', :role, :enabled,"
                        + " :tokenVersion, 0)")
                .bind("id", userId)
                .bind("username", userId)
                .bind("role", role)
                .bind("enabled", enabled)
                .bind("tokenVersion", tokenVersion)
                .then()
                .block();
    }

    private void assign(String userId, String role) {
        client.sql("DELETE FROM access_user_role WHERE user_id = :userId")
                .bind("userId", userId)
                .then()
                .block();
        client.sql("INSERT INTO access_user_role (user_id, role_id, created_at)"
                        + " SELECT :userId, id, 0 FROM access_role WHERE code = :code")
                .bind("userId", userId)
                .bind("code", role)
                .then()
                .block();
    }

    private void scope(String userId, String type, String value) {
        client.sql(
                        "DELETE FROM user_scope WHERE user_id = :userId AND scope_type = :type "
                                + "AND scope_value = :value")
                .bind("userId", userId)
                .bind("type", type)
                .bind("value", value)
                .then()
                .block();
        client.sql(
                        "INSERT INTO user_scope (user_id, scope_type, scope_value) "
                                + "VALUES (:userId, :type, :value)")
                .bind("userId", userId)
                .bind("type", type)
                .bind("value", value)
                .then()
                .block();
    }
}
