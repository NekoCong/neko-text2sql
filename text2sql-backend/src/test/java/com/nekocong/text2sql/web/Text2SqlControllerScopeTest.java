package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.controller.Text2SqlController;
import com.nekocong.text2sql.controller.dto.ChatRequest;
import com.nekocong.text2sql.nl2sql.Text2SqlResult;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.DataScopeResolver;
import com.nekocong.text2sql.service.ConversationService;
import com.nekocong.text2sql.service.Text2SqlPipelineService;
import com.nekocong.text2sql.sql.model.QueryResult;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Controller wiring: every role resolves to its own scope, the resolved instance is what the
 * pipeline receives, and an account with no scope is rejected before any query is built.
 */
@SpringBootTest
class Text2SqlControllerScopeTest {

    @Autowired
    private DataScopeResolver resolver;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private DatabaseClient client;

    private Text2SqlController controller(AtomicReference<DataScope> captured) {
        Text2SqlPipelineService stub =
                new Text2SqlPipelineService(null, null, null, null, null, null, null, null, null) {
                    @Override
                    public Mono<Text2SqlResult> run(
                            DataScope scope, String datasourceId, String question) {
                        captured.set(scope);
                        return Mono.just(
                                new Text2SqlResult(
                                        datasourceId,
                                        "H2",
                                        "SELECT 1",
                                        "stub",
                                        new QueryResult(List.of(), List.of(), 0, false, 0L),
                                        null));
                    }
                };
        return new Text2SqlController(
                        stub, null, conversations, resolver, null, new ObjectMapper());
    }

    @Test
    void studentRequestCarriesTheStudentScope() {
        String userId = seedUser("student", "class_id", "1", "student_id", "1", "student_no", "2023001");
        AtomicReference<DataScope> captured = new AtomicReference<>();

        controller(captured)
                .query(jwt(userId, "student", 0), new ChatRequest("我的成绩", null, null))
                .block();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().isStudent()).isTrue();
        assertThat(captured.get().classIds()).containsExactly(1L);
    }

    @Test
    void counselorRequestCarriesEveryManagedClass() {
        String userId = seedUser("counselor", "class_id", "1");
        insertScope(userId, "class_id", "2");
        AtomicReference<DataScope> captured = new AtomicReference<>();

        controller(captured)
                .query(jwt(userId, "counselor", 0), new ChatRequest("两个班的平均分", null, null))
                .block();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().isCounselor()).isTrue();
        assertThat(captured.get().classIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void adminRequestCarriesTheUnrestrictedScope() {
        String userId = seedUser("admin");
        AtomicReference<DataScope> captured = new AtomicReference<>();

        controller(captured)
                .query(jwt(userId, "admin", 0), new ChatRequest("全校统计", null, null))
                .block();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().isAdmin()).isTrue();
        assertThat(captured.get().bypassesDataScope()).isTrue();
        assertThat(captured.get().classIds()).isEmpty();
    }

    @Test
    void unscopedStudentIsRejectedBeforeThePipelineRuns() {
        // No account row: the token identifies nobody the access layer can resolve.
        String userId = "scope-missing-" + UUID.randomUUID();
        AtomicReference<DataScope> captured = new AtomicReference<>();

        assertThatThrownBy(
                        () ->
                                controller(captured)
                                        .query(jwt(userId, "student", 0), new ChatRequest("我的成绩", null, null))
                                        .block())
                .isInstanceOf(ResponseStatusException.class);

        assertThat(captured.get()).isNull();
    }

    @Test
    void aStaleTokenVersionIsRejectedBeforeThePipelineRuns() {
        String userId = seedUser("student", "class_id", "1", "student_id", "1", "student_no", "2023001");
        AtomicReference<DataScope> captured = new AtomicReference<>();

        assertThatThrownBy(
                        () ->
                                controller(captured)
                                        .query(jwt(userId, "student", 9), new ChatRequest("我的成绩", null, null))
                                        .block())
                .isInstanceOf(ResponseStatusException.class);

        assertThat(captured.get()).isNull();
    }

    @Test
    void aTokenWithoutTheVersionClaimFailsClosed() {
        String userId = seedUser("student", "class_id", "1", "student_id", "1", "student_no", "2023001");
        AtomicReference<DataScope> captured = new AtomicReference<>();

        assertThatThrownBy(
                        () ->
                                controller(captured)
                                        .query(jwt(userId, "student", null), new ChatRequest("我的成绩", null, null))
                                        .block())
                .isInstanceOf(ResponseStatusException.class);

        assertThat(captured.get()).isNull();
    }

    @BeforeEach
    void ensureGrant() {
        ensureStudentCampusGrant();
    }

    private String seedUser(String role, String... scopes) {
        String userId = "scope-" + UUID.randomUUID();
        insertUser(userId, role);
        for (int i = 0; i < scopes.length; i += 2) {
            insertScope(userId, scopes[i], scopes[i + 1]);
        }
        return userId;
    }

    private void insertUser(String userId, String role) {
        client.sql("INSERT INTO app_user (id, username, password_hash, display_name, role, created_at)"
                        + " VALUES (:id, :username, 'x', 'Scope', :role, 0)")
                .bind("id", userId)
                .bind("username", userId)
                .bind("role", role)
                .then()
                .block();
        client.sql("INSERT INTO access_user_role (user_id, role_id, created_at)"
                        + " SELECT :userId, id, 0 FROM access_role WHERE code = :code")
                .bind("userId", userId)
                .bind("code", role)
                .then()
                .block();
    }

    @AfterEach
    void cleanUp() {
        client.sql("DELETE FROM user_scope WHERE user_id LIKE 'scope-%'").then().block();
        client.sql("DELETE FROM access_user_role WHERE user_id LIKE 'scope-%'").then().block();
        client.sql("DELETE FROM app_user WHERE id LIKE 'scope-%'").then().block();
    }

    private void insertScope(String userId, String type, String value) {
        client.sql(
                        "INSERT INTO user_scope (user_id, scope_type, scope_value) "
                                + "VALUES (:userId, :type, :value)")
                .bind("userId", userId)
                .bind("type", type)
                .bind("value", value)
                .then()
                .block();
    }

    /** The registration path relies on the seeded role -> datasource grant still being present. */
    private void ensureStudentCampusGrant() {
        client.sql("INSERT INTO datasource_access (role, datasource_id)"
                        + " SELECT 'student', 'campus' WHERE NOT EXISTS (SELECT 1 FROM datasource_access"
                        + " WHERE role = 'student' AND datasource_id = 'campus')")
                .then()
                .block();
    }

    private static Jwt jwt(String userId, String role) {
        return jwt(userId, role, 0);
    }

    private static Jwt jwt(String userId, String role, Integer version) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject(userId).claim("role", role);
        if (version != null) {
            builder.claim("ver", version);
        }
        return builder.build();
    }
}
