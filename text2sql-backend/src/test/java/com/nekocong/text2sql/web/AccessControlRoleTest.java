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
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The role catalog admin API: create/update/enable/disable with inheritance validation, built-in
 * protection and one audit row per mutation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessControlRoleTest {

    private static final String PASSWORD = "secret123";

    private static final String ROLES = "/api/admin/access-control/roles";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccessControlRepository access;

    @Autowired
    private DatabaseClient db;

    private WebTestClient client;
    private String adminToken;
    private String adminId;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        String username = unique("admin");
        AppUser admin = users.insert(username, passwordEncoder.encode(PASSWORD), "Admin", Roles.ADMIN).block();
        access.assignRoleByCode(admin.id(), Roles.ADMIN).block();
        adminId = admin.id();
        adminToken = TestAuth.login(client, username, PASSWORD);
    }

    @Test
    void createsARoleThatAppearsInTheList() {
        String code = unique("teacher");

        client.post()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "code",
                                code,
                                "name",
                                "教师",
                                "description",
                                "授课教师",
                                "enabled",
                                true))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.id")
                .isNotEmpty()
                .jsonPath("$.code")
                .isEqualTo(code)
                .jsonPath("$.name")
                .isEqualTo("教师")
                .jsonPath("$.builtIn")
                .isEqualTo(false)
                .jsonPath("$.enabled")
                .isEqualTo(true)
                .jsonPath("$.parentId")
                .doesNotExist();

        String id = roleIdByCode(code);

        client.get()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[*].id")
                .value(ids -> assertThat((List<Object>) ids).contains(id))
                .jsonPath("$[0].id")
                .exists();

        assertThat(auditRows(adminId, "role.create", id, "SUCCESS")).isEqualTo(1);
    }

    @Test
    void rejectsADuplicateCode() {
        String code = createRole("讲师", null);

        client.post()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", code, "name", "重复"))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("角色编码已存在");

        assertThat(auditRows(adminId, "role.create", null, "DENIED"))
                .as("one DENIED row for the rejected create")
                .isEqualTo(1);
    }

    @Test
    void updatesNameDescriptionAndParent() {
        String parentCode = createRole("父角色", null);
        String parentId = roleIdByCode(parentCode);
        String childCode = createRole("子角色", null);
        String childId = roleIdByCode(childCode);

        client.put()
                .uri(ROLES + "/" + childId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "name",
                                "子角色（改）",
                                "description",
                                "继承自父角色",
                                "parentId",
                                parentId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(childId)
                .jsonPath("$.name")
                .isEqualTo("子角色（改）")
                .jsonPath("$.description")
                .isEqualTo("继承自父角色")
                .jsonPath("$.parentId")
                .isEqualTo(parentId);

        assertThat(auditRows(adminId, "role.update", childId, "SUCCESS")).isEqualTo(1);
    }

    @Test
    void rejectsACodeChange() {
        String code = createRole("不可改编码", null);
        String id = roleIdByCode(code);

        client.put()
                .uri(ROLES + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", unique("changed"), "name", "改名"))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("角色编码不可修改");

        assertThat(auditRows(adminId, "role.update", id, "DENIED"))
                .as("exactly one DENIED row for the rejected code change")
                .isEqualTo(1);
        assertThat(roleIdByCode(code)).isEqualTo(id);
    }

    @Test
    void rejectsSelfParentAndTwoRoleCycle() {
        String firstCode = createRole("环一", null);
        String firstId = roleIdByCode(firstCode);
        String secondCode = createRole("环二", null);
        String secondId = roleIdByCode(secondCode);

        client.put()
                .uri(ROLES + "/" + firstId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("parentId", firstId))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("会形成循环继承");

        client.put()
                .uri(ROLES + "/" + firstId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("parentId", secondId))
                .exchange()
                .expectStatus()
                .isOk();

        client.put()
                .uri(ROLES + "/" + secondId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("parentId", firstId))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("会形成循环继承");

        assertThat(auditRows(adminId, "role.update", secondId, "DENIED")).isEqualTo(1);
    }

    @Test
    void rejectsAnUnknownParent() {
        String code = createRole("孤儿", null);
        String id = roleIdByCode(code);

        client.put()
                .uri(ROLES + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("parentId", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("父角色不存在");
    }

    @Test
    void rejectsAnUnknownRoleId() {
        client.put()
                .uri(ROLES + "/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "不存在"))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void validatesTheCodeAndName() {
        client.post()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", "Bad Code!", "name", "非法"))
                .exchange()
                .expectStatus()
                .isBadRequest();

        client.post()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", unique("noname")))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void disablesACustomRoleButNotTheBuiltInAdmin() {
        String code = createRole("可停用", null);
        String id = roleIdByCode(code);

        client.put()
                .uri(ROLES + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(false);

        assertThat(auditRows(adminId, "role.disable", id, "SUCCESS")).isEqualTo(1);

        String adminRoleId = roleIdByCode(Roles.ADMIN);
        client.put()
                .uri(ROLES + "/" + adminRoleId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("内置管理员角色不能停用");

        assertThat(auditRows(adminId, "role.disable", adminRoleId, "DENIED")).isEqualTo(1);
        assertThat(roleEnabled(adminRoleId)).isTrue();
    }

    @Test
    void renamingTheBuiltInAdminIsAllowed() {
        String adminRoleId = roleIdByCode(Roles.ADMIN);
        client.put()
                .uri(ROLES + "/" + adminRoleId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "超级管理员"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.builtIn")
                .isEqualTo(true)
                .jsonPath("$.name")
                .isEqualTo("超级管理员");
    }

    @Test
    void aStudentCannotManageRoles() {
        String username = unique("student");
        AppUser student =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Student", Roles.STUDENT).block();
        access.assignRoleByCode(student.id(), Roles.STUDENT).block();
        String token = TestAuth.login(client, username, PASSWORD);

        client.get()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isForbidden();

        client.post()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", unique("nope"), "name", "无权限"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    // --- helpers ----------------------------------------------------------------------------

    private String bearer() {
        return "Bearer " + adminToken;
    }

    /** Creates a role through the API and returns its code. */
    private String createRole(String name, String parentId) {
        String code = unique("role");
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("code", code);
        body.put("name", name);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        client.post()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isCreated();
        return code;
    }

    private String roleIdByCode(String code) {
        String id =
                db.sql("SELECT id FROM access_role WHERE code = :code")
                        .bind("code", code)
                        .map((row, meta) -> row.get("id", String.class))
                        .one()
                        .block();
        assertThat(id).as("role " + code).isNotNull();
        return id;
    }

    private boolean roleEnabled(String roleId) {
        Boolean enabled =
                db.sql("SELECT enabled FROM access_role WHERE id = :id")
                        .bind("id", roleId)
                        .map((row, meta) -> Boolean.TRUE.equals(row.get("enabled", Boolean.class)))
                        .one()
                        .block();
        return Boolean.TRUE.equals(enabled);
    }

    /** Counts audit rows matching the actor/action/result, with an optional entity id. */
    private long auditRows(String actorId, String action, String entityId, String result) {
        String sql =
                "SELECT COUNT(*) AS n FROM access_audit"
                        + " WHERE actor_id = :actorId AND action = :action AND result = :result"
                        + (entityId == null ? "" : " AND entity_id = :entityId");
        DatabaseClient.GenericExecuteSpec spec =
                db.sql(sql)
                        .bind("actorId", actorId)
                        .bind("action", action)
                        .bind("result", result);
        if (entityId != null) {
            spec = spec.bind("entityId", entityId);
        }
        Long count =
                spec.map((row, meta) -> row.get("n", Long.class)).one().block();
        return count == null ? 0L : count;
    }

    private static String readId(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("id").asText();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse role id", ex);
        }
    }

    /** A unique, pattern-valid code (lowercase start, then alphanumerics). */
    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
