package com.nekocong.text2sql.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.datasource.DatasourceAccessGuard;
import com.nekocong.text2sql.nl2sql.ExecutionListener;
import com.nekocong.text2sql.nl2sql.Text2SqlResult;
import com.nekocong.text2sql.schema.SchemaIntrospector;
import com.nekocong.text2sql.schema.SchemaRenderer;
import com.nekocong.text2sql.security.AccessFixtures;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.TablePolicy;
import com.nekocong.text2sql.service.Text2SqlPipelineService;
import com.nekocong.text2sql.sql.model.QueryResult;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The agent tools must execute with the caller's scope and never leak metadata outside it. The
 * {@link DataScope} parameter is context-injected by AgentScope, so the tool simply has to pass it
 * through to the pipeline.
 */
@SpringBootTest
class SqlToolsTest {

    @Autowired
    private DataSourceRegistry registry;

    @Autowired
    private SchemaIntrospector introspector;

    @Autowired
    private SchemaRenderer renderer;

    @Autowired
    private DatasourceAccessGuard guard;

    private SqlTools tools(AtomicReference<DataScope> captured) {
        Text2SqlPipelineService stub =
                new Text2SqlPipelineService(null, null, null, null, null, null, null, null, null) {
                    @Override
                    public Text2SqlResult runBlocking(
                            DataScope scope,
                            String datasourceId,
                            String question,
                            ExecutionListener listener) {
                        captured.set(scope);
                        return new Text2SqlResult(
                                datasourceId,
                                "H2",
                                "SELECT 1",
                                "stub",
                                new QueryResult(List.of(), List.of(), 0, false, 0L),
                                null);
                    }
                };
        return new SqlTools(
                registry, introspector, renderer, stub, new TablePolicy(), guard, new ObjectMapper());
    }

    @Test
    void queryDatabasePassesTheCallerScopeThrough() {
        AtomicReference<DataScope> captured = new AtomicReference<>();
        DataScope scope = studentScope();

        String json = tools(captured).queryDatabase("我的成绩", null, scope, ExecutionListener.noop());

        assertThat(captured.get()).isSameAs(scope);
        assertThat(json).contains("\"sql\":\"SELECT 1\"");
    }

    @Test
    void listTablesReturnsOnlyScopedTables() {
        String tables = tools(new AtomicReference<>()).listTables(null, studentScope());

        assertThat(tables.split(","))
                .allSatisfy(name -> assertThat(List.of("class", "course", "student", "score", "attendance"))
                        .contains(name.trim()));
        assertThat(tables.split(",")).hasSize(5);
    }

    @Test
    void listTablesIsUnrestrictedOnlyForABypassScope() {
        String tables = tools(new AtomicReference<>()).listTables(null, AccessFixtures.admin());

        assertThat(tables).contains("student").contains("score");
    }

    @Test
    void describeTableReportsUnknownTable() {
        String answer = tools(new AtomicReference<>()).describeTable("salary", null, studentScope());

        assertThat(answer).contains("Unknown table 'salary'");
    }

    @Test
    void queryDatabasePropagatesPipelineFailures() {
        Text2SqlPipelineService failing =
                new Text2SqlPipelineService(null, null, null, null, null, null, null, null, null) {
                    @Override
                    public Text2SqlResult runBlocking(
                            DataScope scope,
                            String datasourceId,
                            String question,
                            ExecutionListener listener) {
                        throw new IllegalStateException("denied for test");
                    }
                };
        SqlTools tools =
                new SqlTools(
                        registry, introspector, renderer, failing, new TablePolicy(), guard, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> tools.queryDatabase("q", "campus", studentScope(), ExecutionListener.noop()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void metadataToolsRespectTheDatasourceWhitelist() {
        DataScope noGrant = new DataScope("u-1", "student", Set.of(1L), 1L, "2023001", Set.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> tools(new AtomicReference<>()).listTables("warehouse", studentScope()))
                .isInstanceOf(com.nekocong.text2sql.security.SqlPermissionException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> tools(new AtomicReference<>()).describeTable("student", "warehouse", studentScope()))
                .isInstanceOf(com.nekocong.text2sql.security.SqlPermissionException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> tools(new AtomicReference<>()).listTables(null, noGrant))
                .hasMessageContaining("没有任何可用的数据源");
    }

    private static DataScope studentScope() {
        return AccessFixtures.student();
    }
}
