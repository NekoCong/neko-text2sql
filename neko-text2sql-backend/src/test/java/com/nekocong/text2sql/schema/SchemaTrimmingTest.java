package com.nekocong.text2sql.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.datasource.DatasourceAccessGuard;
import com.nekocong.text2sql.security.AccessFixtures;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.TablePolicy;
import com.nekocong.text2sql.tool.SqlTools;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the schema/metadata trimming actually depends on the caller: a table that exists in the
 * datasource but is outside the campus whitelist must be invisible to a student and visible to an
 * admin. Without a non-whitelisted table in the database, the older assertions would pass even if
 * the filter were deleted.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaTrimmingTest {

    private static final String HIDDEN_TABLE = "teacher_salary";

    @Autowired
    private DataSourceRegistry registry;

    @Autowired
    private FullSchemaProvider fullSchemaProvider;

    @Autowired
    private SchemaIntrospector introspector;

    @Autowired
    private SchemaRenderer renderer;

    @Autowired
    private DatasourceAccessGuard guard;

    @BeforeAll
    void createHiddenTable() throws Exception {
        execute(
                "CREATE TABLE IF NOT EXISTS "
                        + HIDDEN_TABLE
                        + " (id BIGINT PRIMARY KEY, amount DECIMAL(10,2), note VARCHAR(100))");
    }

    @AfterAll
    void dropHiddenTable() throws Exception {
        execute("DROP TABLE IF EXISTS " + HIDDEN_TABLE);
    }

    @Test
    void studentSchemaHidesTheNonWhitelistedTable() {
        String ddl = fullSchemaProvider.renderForPrompt("campus", studentScope(), "工资");

        assertThat(ddl).doesNotContainIgnoringCase(HIDDEN_TABLE);
        assertThat(ddl).containsIgnoringCase("CREATE TABLE student");
    }

    @Test
    void adminSchemaIncludesEveryTable() {
        String ddl = fullSchemaProvider.renderForPrompt("campus", AccessFixtures.admin(), "工资");

        assertThat(ddl).containsIgnoringCase(HIDDEN_TABLE);
    }

    @Test
    void aScopeWithoutResolvedAccessSeesNothing() {
        // Fail closed: the legacy 6-arg constructor carries no access, so no table is visible.
        DataScope legacy = new DataScope("u", "student", Set.of(1L), 1L, "2023001", Set.of("campus"));

        assertThat(fullSchemaProvider.renderForPrompt("campus", legacy, "工资"))
                .doesNotContainIgnoringCase("CREATE TABLE");
    }

    @Test
    void noResolvedAccessMeansNoTablesEvenWithTheDefaultDatasource() {
        // A default datasource (null id) must not rescue a scope that has no resolved access.
        DataScope legacy = new DataScope("u", "student", Set.of(1L), 1L, "2023001", Set.of("campus"));

        assertThat(fullSchemaProvider.renderForPrompt(null, legacy, "工资"))
                .doesNotContainIgnoringCase("CREATE TABLE");
    }

    @Test
    void studentMetadataToolsCannotSeeTheHiddenTable() {
        SqlTools tools = tools();

        assertThat(tools.listTables(null, studentScope())).doesNotContain(HIDDEN_TABLE);
        assertThat(tools.describeTable(HIDDEN_TABLE, null, studentScope()))
                .contains("Unknown table");
    }

    @Test
    void adminMetadataToolsSeeTheHiddenTable() {
        SqlTools tools = tools();

        assertThat(tools.listTables(null, AccessFixtures.admin())).contains(HIDDEN_TABLE);
        assertThat(tools.describeTable(HIDDEN_TABLE, null, AccessFixtures.admin()))
                .containsIgnoringCase("CREATE TABLE " + HIDDEN_TABLE);
    }

    private SqlTools tools() {
        return new SqlTools(registry, introspector, renderer, null, new TablePolicy(), guard, new ObjectMapper());
    }

    private void execute(String sql) throws Exception {
        DataSource dataSource = registry.resolve(null);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static DataScope studentScope() {
        return AccessFixtures.student();
    }
}
