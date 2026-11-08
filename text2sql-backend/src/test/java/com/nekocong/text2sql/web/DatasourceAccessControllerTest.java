package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.datasource.DatasourceAccessGuard;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.DataScopeResolver;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.user.AppUser;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The admin page API: only admins may read or change the role -> datasource whitelist, and a change
 * takes effect on the next request without a restart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatasourceAccessControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccessControlRepository access;

    @Autowired
    private DataScopeResolver resolver;

    @Autowired
    private DatasourceAccessGuard guard;

    @Autowired
    private DatabaseClient db;

    private WebTestClient client;
    private String adminToken;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        adminToken = adminToken();
        grant("student", "campus", adminToken);
        grant("counselor", "campus", adminToken);
    }

    @AfterEach
    void restore() {
        grant("student", "campus", adminToken);
        grant("counselor", "campus", adminToken);
    }

    @Test
    void adminReadsTheMatrix() {
        client.get()
                .uri("/api/admin/datasource-access")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roles[0]")
                .isEqualTo("student")
                .jsonPath("$.datasources[0].id")
                .isEqualTo("campus")
                .jsonPath("$.datasources[0].defaultDatasource")
                .isEqualTo(true)
                .jsonPath("$.datasources[0].grantedRoles")
                .value(org.hamcrest.Matchers.hasItem("student"));
    }

    @Test
    void nonAdminIsRejected() {
        String studentToken = TestAuth.registerAndLogin(client);

        client.get()
                .uri("/api/admin/datasource-access")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void revokeTakesEffectOnTheNextRequest() {
        AppUser user = studentUser();
        revoke("student", "campus", adminToken);

        DataScope student = resolver.resolve(user.id(), "student").block();

        assertThat(student).isNotNull();
        assertThat(student.allowedDatasources()).isEmpty();
        assertThatThrownBy(() -> guard.resolve(student, null))
                .hasMessageContaining("没有任何可用的数据源");

        grant("student", "campus", adminToken);

        DataScope restored = resolver.resolve(user.id(), "student").block();
        assertThat(restored).isNotNull();
        assertThat(guard.resolve(restored, null)).isEqualTo("campus");
    }

    @Test
    void revokedDatasourceMakesTheSyncQueryReturn403() {
        String username = "student" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        AppUser user = users.insert(username, passwordEncoder.encode("secret123"), "Student", Roles.STUDENT).block();
        access.assignRoleByCode(user.id(), Roles.STUDENT).block();
        insertScope(user.id(), "class_id", "1");
        insertScope(user.id(), "student_id", "1");
        insertScope(user.id(), "student_no", "2023001");
        String token = TestAuth.login(client, username, "secret123");

        revoke("student", "campus", adminToken);

        client.post()
                .uri("/api/text2sql/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"我的成绩\"}")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.message")
                .exists();
    }

    @Test
    void unknownRoleOrDatasourceIsRejected() {
        client.put()
                .uri("/api/admin/datasource-access/teacher/campus")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus()
                .isBadRequest();
        client.put()
                .uri("/api/admin/datasource-access/student/missing")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    private String adminToken() {
        String username = "admin" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        AppUser admin = users.insert(username, passwordEncoder.encode("secret123"), "Admin", Roles.ADMIN).block();
        access.assignRoleByCode(admin.id(), Roles.ADMIN).block();
        return TestAuth.login(client, username, "secret123");
    }

    private AppUser studentUser() {
        String username = "student" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        AppUser user = users.insert(username, passwordEncoder.encode("secret123"), "Student", Roles.STUDENT).block();
        access.assignRoleByCode(user.id(), Roles.STUDENT).block();
        insertScope(user.id(), "class_id", "1");
        insertScope(user.id(), "student_id", "1");
        insertScope(user.id(), "student_no", "2023001");
        return user;
    }

    private void insertScope(String userId, String type, String value) {
        db.sql(
                        "INSERT INTO user_scope (user_id, scope_type, scope_value) "
                                + "VALUES (:userId, :type, :value)")
                .bind("userId", userId)
                .bind("type", type)
                .bind("value", value)
                .then()
                .block();
    }

    private void grant(String role, String datasourceId, String token) {
        client.put()
                .uri("/api/admin/datasource-access/" + role + "/" + datasourceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk();
    }

    private void revoke(String role, String datasourceId, String token) {
        client.delete()
                .uri("/api/admin/datasource-access/" + role + "/" + datasourceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk();
    }
}

