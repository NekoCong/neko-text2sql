package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.user.AppUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The read-only datasource schema endpoint backing the data-scope editor: a known datasource returns
 * its live tables/columns (never connection secrets), an unknown one is a 404, and a non-admin is
 * forbidden.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessControlSchemaTest {

    private static final String PASSWORD = "secret123";

    private static final String SCHEMA = "/api/admin/access-control/datasources/{id}/schema";

    private static final String DATASOURCE = "campus";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccessControlRepository access;

    private WebTestClient client;
    private String adminToken;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        String username = unique("admin");
        AppUser admin =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Admin", Roles.ADMIN).block();
        access.assignRoleByCode(admin.id(), Roles.ADMIN).block();
        adminToken = TestAuth.login(client, username, PASSWORD);
    }

    @Test
    void aKnownDatasourceReturnsItsTablesAndColumnsWithoutSecrets() {
        client.get()
                .uri(SCHEMA, DATASOURCE)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.datasourceId")
                .isEqualTo(DATASOURCE)
                .jsonPath("$.tables")
                .isNotEmpty();

        Map<String, Object> schema =
                client.get()
                        .uri(SCHEMA, DATASOURCE)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                        .returnResult()
                        .getResponseBody();
        assertThat(schema).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) schema.get("tables");
        List<String> tableNames = tables.stream().map(t -> String.valueOf(t.get("name"))).toList();
        assertThat(tableNames)
                .as("the demo schema tables are listed by name")
                .contains("class", "course", "student", "score", "attendance");

        Map<String, Object> student =
                tables.stream()
                        .filter(t -> "student".equals(t.get("name")))
                        .findFirst()
                        .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) student.get("columns");
        assertThat(columns).isNotEmpty();
        assertThat(columns.stream().map(c -> String.valueOf(c.get("name"))))
                .contains("id", "name", "student_no");
        // Every column reports a non-blank introspected type name and never any connection detail.
        assertThat(columns)
                .allSatisfy(
                        column -> {
                            assertThat(column.get("name")).isNotNull();
                            assertThat(String.valueOf(column.get("typeName"))).isNotBlank();
                            assertThat(column).doesNotContainKeys("url", "username", "password", "driver");
                        });
    }

    @Test
    void anUnknownDatasourceIsNotFoundWithTheStatusMessageShape() {
        client.get()
                .uri(SCHEMA, "no-such-ds")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(404)
                .jsonPath("$.message")
                .isEqualTo("数据源不存在");
    }

    @Test
    void aStudentCannotReadTheSchema() {
        String username = unique("student");
        AppUser student =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Student", Roles.STUDENT)
                        .block();
        access.assignRoleByCode(student.id(), Roles.STUDENT).block();
        String token = TestAuth.login(client, username, PASSWORD);

        client.get()
                .uri(SCHEMA, DATASOURCE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    private String bearer() {
        return "Bearer " + adminToken;
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
