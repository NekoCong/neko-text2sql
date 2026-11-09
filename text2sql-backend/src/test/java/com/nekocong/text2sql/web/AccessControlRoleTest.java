package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.user.AppUser;
import java.util.HashMap;
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
        AppUser admin =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Admin", Roles.ADMIN).block();
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
        assertThat(auditRows("role.create", id, "SUCCESS"))
                .as("the role row and its audit row are committed together")
                .isEqualTo(1);
        assertThat(roleCountByCode(code)).isEqualTo(1);

        client.get()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[*].id")
                .value(ids -> assertThat((List<Object>) ids).contains(id));
    }

    @Test
    void listsRolesOrderedByCode() {
        List<String> codes =
                client.get()
                        .uri(ROLES)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBodyList(Map.class)
                        .returnResult()
                        .getResponseBody()
                        .stream()
                        .map(row -> String.valueOf(row.get("code")))
                        .toList();

        assertThat(codes).isNotEmpty().isSorted();
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

        assertThat(auditRows("role.create", roleIdByCode(code), "DENIED"))
                .as("one DENIED row for the rejected create")
                .isEqualTo(1);
    }

    @Test
    void updatesNameDescriptionAndParent() {
        String parentId = roleIdByCode(createRole("父角色", null));
        String childId = roleIdByCode(createRole("子角色", null));

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

        assertThat(auditRows("role.update", childId, "SUCCESS")).isEqualTo(1);
        assertThat(roleName(childId)).isEqualTo("子角色（改）");
    }

    @Test
    void omittingParentOnUpdateKeepsIt() {
        String parentId = roleIdByCode(createRole("父（保留）", null));
        String childId = roleIdByCode(createRole("子（保留）", null));
        put(childId, Map.of("parentId", parentId)).expectStatus().isOk();

        // No parentId at all: keep the existing parent, change only the name.
        put(childId, Map.of("name", "只改名"))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.parentId")
                .isEqualTo(parentId)
                .jsonPath("$.name")
                .isEqualTo("只改名");

        // An explicit null is the same as omitting it.
        put(childId, Map.of("name", "再改名"))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.parentId")
                .isEqualTo(parentId);
    }

    @Test
    void emptyStringClearsTheParent() {
        String parentId = roleIdByCode(createRole("父（清除）", null));
        String childId = roleIdByCode(createRole("子（清除）", null));
        put(childId, Map.of("parentId", parentId)).expectStatus().isOk();

        client.put()
                .uri(ROLES + "/" + childId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("parentId", ""))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.parentId")
                .doesNotExist();

        assertThat(auditRows("role.update", childId, "SUCCESS")).isEqualTo(2);
    }

    @Test
    void allowsADisabledParent() {
        String parentId = roleIdByCode(createRole("停用的父", null));
        put(parentId, Map.of("enabled", false)).expectStatus().isOk();

        String childId = roleIdByCode(createRole("停用父的子", null));
        put(childId, Map.of("parentId", parentId))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.parentId")
                .isEqualTo(parentId);
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

        assertThat(auditRows("role.update", id, "DENIED"))
                .as("exactly one DENIED row for the rejected code change")
                .isEqualTo(1);
        assertThat(roleIdByCode(code)).isEqualTo(id);
    }

    @Test
    void rejectsSelfParentAndTwoRoleCycle() {
        String firstId = roleIdByCode(createRole("环一", null));
        String secondId = roleIdByCode(createRole("环二", null));

        put(firstId, Map.of("parentId", firstId))
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("会形成循环继承");

        put(firstId, Map.of("parentId", secondId)).expectStatus().isOk();

        put(secondId, Map.of("parentId", firstId))
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("会形成循环继承");

        assertThat(auditRows("role.update", secondId, "DENIED")).isEqualTo(1);
    }

    @Test
    void rejectsACycleThroughADeepAncestor() {
        String topId = roleIdByCode(createRole("深一", null));
        String midId = roleIdByCode(createRole("深二", null));
        String leafId = roleIdByCode(createRole("深三", null));

        put(midId, Map.of("parentId", topId)).expectStatus().isOk();
        put(leafId, Map.of("parentId", midId)).expectStatus().isOk();

        // top -> mid -> leaf already exists; making top a child of leaf would loop.
        put(topId, Map.of("parentId", leafId))
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("会形成循环继承");

        assertThat(auditRows("role.update", topId, "DENIED")).isEqualTo(1);
    }

    @Test
    void rejectsTheBuiltInAdminRoleAsAParent() {
        String adminRoleId = roleIdByCode(Roles.ADMIN);

        client.post()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "code", unique("escalate"),
                                "name", "越权",
                                "parentId", adminRoleId))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("内置管理员角色不能作为父角色");

        String ownId = roleIdByCode(createRole("自建角色", null));
        put(ownId, Map.of("parentId", adminRoleId))
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("内置管理员角色不能作为父角色");

        assertThat(auditRows("role.create", adminRoleId, "DENIED")).isEqualTo(1);
        assertThat(auditRows("role.update", ownId, "DENIED")).isEqualTo(1);
    }

    @Test
    void rejectsAnUnknownParent() {
        String id = roleIdByCode(createRole("孤儿", null));

        put(id, Map.of("parentId", UUID.randomUUID().toString()))
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("父角色不存在");

        assertThat(auditRows("role.update", id, "DENIED")).isEqualTo(1);
    }

    @Test
    void rejectsAnUnknownRoleIdAndAuditsIt() {
        String unknown = UUID.randomUUID().toString();

        client.put()
                .uri(ROLES + "/" + unknown)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "不存在"))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("角色不存在");

        assertThat(auditRows("role.update", unknown, "DENIED"))
                .as("the rejected request is filed against the requested id")
                .isEqualTo(1);
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

        client.post()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", unique("long"), "name", "名".repeat(101)))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void rejectsAnOversizedDescription() {
        client.post()
                .uri(ROLES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "code", unique("descr"),
                                "name", "描述过长",
                                "description", "描".repeat(513)))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void disablesACustomRoleButNotTheBuiltInAdmin() {
        String id = roleIdByCode(createRole("可停用", null));

        put(id, Map.of("enabled", false))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(false);

        assertThat(auditRows("role.disable", id, "SUCCESS")).isEqualTo(1);

        String adminRoleId = roleIdByCode(Roles.ADMIN);
        put(adminRoleId, Map.of("enabled", false))
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("内置管理员角色不能停用");

        assertThat(auditRows("role.disable", adminRoleId, "DENIED")).isEqualTo(1);
        assertThat(roleEnabled(adminRoleId)).isTrue();
    }

    @Test
    void renamingTheBuiltInAdminIsAllowedAndRestored() {
        String adminRoleId = roleIdByCode(Roles.ADMIN);
        String originalName = roleName(adminRoleId);
        try {
            put(adminRoleId, Map.of("name", "超级管理员"))
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.builtIn")
                    .isEqualTo(true)
                    .jsonPath("$.name")
                    .isEqualTo("超级管理员");
        } finally {
            // The H2 database is shared across test contexts: restore the seeded name.
            put(adminRoleId, Map.of("name", originalName)).expectStatus().isOk();
        }
        assertThat(roleName(adminRoleId)).isEqualTo(originalName);
    }

    @Test
    void aStudentCannotManageRoles() {
        String username = unique("student");
        AppUser student =
                users.insert(username, passwordEncoder.encode(PASSWORD), "Student", Roles.STUDENT)
                        .block();
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

    private WebTestClient.ResponseSpec put(String roleId, Map<String, Object> body) {
        return client.put()
                .uri(ROLES + "/" + roleId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    /** Creates a role through the API and returns its code. */
    private String createRole(String name, String parentId) {
        String code = unique("role");
        Map<String, Object> body = new HashMap<>();
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

    private long roleCountByCode(String code) {
        Long count =
                db.sql("SELECT COUNT(*) AS n FROM access_role WHERE code = :code")
                        .bind("code", code)
                        .map((row, meta) -> row.get("n", Long.class))
                        .one()
                        .block();
        return count == null ? 0L : count;
    }

    private String roleName(String roleId) {
        return db.sql("SELECT name FROM access_role WHERE id = :id")
                .bind("id", roleId)
                .map((row, meta) -> row.get("name", String.class))
                .one()
                .block();
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

    /**
     * Counts audit rows for this test's admin actor and the given action/result.
     *
     * @param entityId the entity id to filter by, or {@code null} to apply <em>no</em> id filter
     *     (never {@code IS NULL})
     */
    private long auditRows(String action, String entityId, String result) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT COUNT(*) AS n FROM access_audit"
                                + " WHERE actor_id = :actorId AND action = :action AND result = :result");
        if (entityId != null) {
            sql.append(" AND entity_id = :entityId");
        }
        DatabaseClient.GenericExecuteSpec spec =
                db.sql(sql.toString())
                        .bind("actorId", adminId)
                        .bind("action", action)
                        .bind("result", result);
        if (entityId != null) {
            spec = spec.bind("entityId", entityId);
        }
        Long count = spec.map((row, meta) -> row.get("n", Long.class)).one().block();
        return count == null ? 0L : count;
    }

    /** A unique, pattern-valid code (lowercase start, then alphanumerics). */
    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
