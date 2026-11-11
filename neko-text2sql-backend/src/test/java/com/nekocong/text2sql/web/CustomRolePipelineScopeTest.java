package com.nekocong.text2sql.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.AccessContextResolver;
import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.access.DataScopePolicy;
import com.nekocong.text2sql.access.EffectiveAccess;
import com.nekocong.text2sql.access.Permissions;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.datasource.DatasourceAccessGuard;
import com.nekocong.text2sql.gbi.ChartInference;
import com.nekocong.text2sql.modelconfig.ModelResolver;
import com.nekocong.text2sql.nl2sql.PromptAssembler;
import com.nekocong.text2sql.nl2sql.SqlGeneration;
import com.nekocong.text2sql.nl2sql.SqlGenerationService;
import com.nekocong.text2sql.nl2sql.SqlResultParser;
import com.nekocong.text2sql.nl2sql.Text2SqlResult;
import com.nekocong.text2sql.prompt.PromptCatalog;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.schema.FullSchemaProvider;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.security.TablePolicy;
import com.nekocong.text2sql.service.Text2SqlPipelineService;
import com.nekocong.text2sql.sql.DataScopeRewriter;
import com.nekocong.text2sql.sql.SqlExecutor;
import com.nekocong.text2sql.sql.SqlSafetyValidator;
import com.nekocong.text2sql.sql.model.QueryResult;
import com.nekocong.text2sql.user.AppUser;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A custom role configured through the real admin API drives the real H2-backed NL2SQL pipeline.
 * SQL generation is stubbed, but schema grounding, policy resolution, rewriting and JDBC execution
 * are real. Revoking the role closes the very next resolver-to-pipeline call before another query.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomRolePipelineScopeTest {

    private static final String PASSWORD = "secret123";
    private static final String BASE = "/api/admin/access-control";
    private static final String ROLES = BASE + "/roles";
    private static final String USERS = BASE + "/users";
    private static final String DATA_SCOPES = BASE + "/data-scopes";
    private static final String DATASOURCE = "campus";
    private static final long STUDENT_ID = 2L;

    private static final Model DUMMY_MODEL =
            new Model() {
                @Override
                public Flux<ChatResponse> stream(
                        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                    return Flux.empty();
                }

                @Override
                public String getModelName() {
                    return "test-dummy";
                }
            };

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccessControlRepository access;

    @Autowired
    private AccessContextResolver resolver;

    @Autowired
    private DatabaseClient db;

    @Autowired
    private DataSourceRegistry registry;

    @Autowired
    private FullSchemaProvider schemaProvider;

    @Autowired
    private SqlSafetyValidator safetyValidator;

    @Autowired
    private SqlExecutor realExecutor;

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
    void customRoleFiltersRealPipelineAndRevocationFailsClosed() {
        String roleCode = unique("pipeline");
        String roleId = createRole(roleCode);
        grantChatUse(roleId);
        grantCampus(roleId);
        createStudentIdFilter(roleId);

        String username = unique("customuser");
        String userId = createUser(username, roleId);
        insertStudentPrincipal(userId);
        assertThat(TestAuth.login(client, username, PASSWORD)).isNotBlank();
        int tokenVersion = tokenVersion(userId);

        EffectiveAccess effective = resolver.resolve(userId, tokenVersion).block();
        assertThat(effective).isNotNull();
        assertThat(effective.roleCodes()).containsExactly(roleCode);
        assertThat(effective.permissions()).contains(Permissions.CHAT_USE);
        assertThat(effective.datasourceIds()).containsExactly(DATASOURCE);
        assertThat(effective.principal().studentId()).isEqualTo(STUDENT_ID);
        assertThat(effective.dataScopes())
                .singleElement()
                .satisfies(rule -> {
                    assertThat(rule.datasourceId()).isEqualTo(DATASOURCE);
                    assertThat(rule.tableName()).isEqualTo("score");
                    assertThat(rule.policy()).isEqualTo(DataScopePolicy.FILTER);
                    assertThat(rule.columnName()).isEqualTo("student_id");
                    assertThat(rule.principalAttribute()).isEqualTo("studentId");
                });

        CountingExecutor executor = new CountingExecutor(realExecutor);
        Text2SqlPipelineService pipeline = pipelineReturningUnfilteredScoreQuery(executor);
        Text2SqlResult result =
                pipeline.runBlocking(DataScope.from(effective), DATASOURCE, "我的成绩");

        assertThat(executor.calls.get()).isEqualTo(1);
        assertThat(executor.executedSql.get()).isEqualTo(result.sql());
        assertThat(result.sql()).containsIgnoringCase("student_id IN (2)");
        assertThat(result.result().rowCount()).isEqualTo(4);
        assertThat(result.result().rows())
                .allSatisfy(row -> assertThat(((Number) row.get("student_id")).longValue())
                        .isEqualTo(STUDENT_ID));
        assertThat(result.result().rows())
                .extracting(row -> ((Number) row.get("id")).longValue())
                .containsExactlyInAnyOrder(5L, 6L, 7L, 8L);

        client.put()
                .uri(USERS + "/" + userId + "/roles")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("roleIds", List.of()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roles.length()")
                .isEqualTo(0);

        // This is the production order: resolve fresh access, then invoke the pipeline. With no
        // usable role, resolution denies the chained call before generation or SQL execution.
        Mono<Text2SqlResult> revokedCall =
                resolver.resolve(userId, tokenVersion(userId))
                        .flatMap(resolved -> pipeline.run(
                                DataScope.from(resolved), DATASOURCE, "我的成绩"));
        assertThatThrownBy(revokedCall::block)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException denied = (ResponseStatusException) error;
                    assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(denied.getReason()).contains("没有可用角色");
                });
        assertThat(executor.calls.get())
                .as("revocation must not allow a second, unfiltered query")
                .isEqualTo(1);
    }

    private String createRole(String code) {
        Map<String, Object> response =
                client.post()
                        .uri(ROLES)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("code", code, "name", "Pipeline scope", "enabled", true))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .returnResult()
                        .getResponseBody();
        assertThat(response).isNotNull();
        return String.valueOf(response.get("id"));
    }

    private void grantChatUse(String roleId) {
        client.put()
                .uri(ROLES + "/" + roleId + "/permissions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("permissions", List.of(Permissions.CHAT_USE)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.permissions[0]")
                .isEqualTo(Permissions.CHAT_USE);
    }

    private void grantCampus(String roleId) {
        client.put()
                .uri(ROLES + "/" + roleId + "/datasources")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("datasourceIds", List.of(DATASOURCE)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.datasourceIds[0]")
                .isEqualTo(DATASOURCE);
    }

    private void createStudentIdFilter(String roleId) {
        client.put()
                .uri(DATA_SCOPES)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "roleId", roleId,
                        "datasourceId", DATASOURCE,
                        "tableName", "score",
                        "policy", "FILTER",
                        "columnName", "student_id",
                        "principalAttribute", "studentId"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.policy")
                .isEqualTo("FILTER")
                .jsonPath("$.columnName")
                .isEqualTo("student_id")
                .jsonPath("$.principalAttribute")
                .isEqualTo("studentId");
    }

    private String createUser(String username, String roleId) {
        Map<String, Object> response =
                client.post()
                        .uri(USERS)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(
                                "username", username,
                                "password", PASSWORD,
                                "displayName", "Custom role student",
                                "roleIds", List.of(roleId)))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .returnResult()
                        .getResponseBody();
        assertThat(response).isNotNull();
        return String.valueOf(response.get("id"));
    }

    private void insertStudentPrincipal(String userId) {
        Long updated =
                db.sql("INSERT INTO user_scope (user_id, scope_type, scope_value)"
                                + " VALUES (:userId, 'student_id', :studentId)")
                        .bind("userId", userId)
                        .bind("studentId", String.valueOf(STUDENT_ID))
                        .fetch()
                        .rowsUpdated()
                        .block();
        assertThat(updated).isEqualTo(1L);
    }

    private int tokenVersion(String userId) {
        Integer version =
                db.sql("SELECT token_version FROM app_user WHERE id = :userId")
                        .bind("userId", userId)
                        .map((row, metadata) -> row.get("token_version", Integer.class))
                        .one()
                        .block();
        assertThat(version).isNotNull();
        return version;
    }

    private Text2SqlPipelineService pipelineReturningUnfilteredScoreQuery(SqlExecutor executor) {
        SqlGenerationService stub =
                new SqlGenerationService(
                        new PromptAssembler(new PromptCatalog()), new SqlResultParser(new ObjectMapper())) {
                    @Override
                    public Mono<SqlGeneration> generate(
                            Model model,
                            String dialect,
                            String schema,
                            String question,
                            String callerContext) {
                        return Mono.just(new SqlGeneration("SELECT * FROM score", "原始说明"));
                    }
                };
        ModelResolver modelResolver =
                new ModelResolver(null, null, null) {
                    @Override
                    public Mono<Model> resolve(String userId) {
                        return Mono.just(DUMMY_MODEL);
                    }
                };
        return new Text2SqlPipelineService(
                registry,
                schemaProvider,
                modelResolver,
                stub,
                safetyValidator,
                new DataScopeRewriter(new TablePolicy()),
                new DatasourceAccessGuard(registry),
                executor,
                new ChartInference());
    }

    private String bearer() {
        return "Bearer " + adminToken;
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** Delegates to the real JDBC executor while capturing every statement and counting calls. */
    private static final class CountingExecutor extends SqlExecutor {
        private final SqlExecutor delegate;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> executedSql = new AtomicReference<>();

        private CountingExecutor(SqlExecutor delegate) {
            super(null, null);
            this.delegate = delegate;
        }

        @Override
        public Mono<QueryResult> execute(String datasourceId, String sql) {
            calls.incrementAndGet();
            executedSql.set(sql);
            return delegate.execute(datasourceId, sql);
        }

        @Override
        public QueryResult executeBlocking(String datasourceId, String sql) {
            calls.incrementAndGet();
            executedSql.set(sql);
            return delegate.executeBlocking(datasourceId, sql);
        }
    }
}
