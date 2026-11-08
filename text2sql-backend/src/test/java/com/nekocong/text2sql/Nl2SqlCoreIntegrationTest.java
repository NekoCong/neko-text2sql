package com.nekocong.text2sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.schema.FullSchemaProvider;
import com.nekocong.text2sql.schema.SchemaIntrospector;
import com.nekocong.text2sql.schema.model.TableSchema;
import com.nekocong.text2sql.security.AccessFixtures;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.sql.SqlExecutor;
import com.nekocong.text2sql.sql.SqlSafetyValidator;
import com.nekocong.text2sql.sql.model.QueryResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test for the deterministic NL2SQL core against the seeded H2 campus database — no LLM
 * required. Exercises introspection, schema rendering and read-only execution end to end.
 */
@SpringBootTest
class Nl2SqlCoreIntegrationTest {

    @Autowired
    private DataSourceRegistry registry;

    @Autowired
    private SchemaIntrospector introspector;

    @Autowired
    private FullSchemaProvider schemaProvider;

    @Autowired
    private SqlExecutor sqlExecutor;

    @Autowired
    private SqlSafetyValidator safetyValidator;

    @Test
    void introspectsSeededCampusSchema() {
        List<TableSchema> tables = introspector.introspect(registry.resolve(null));
        List<String> names = tables.stream().map(TableSchema::name).map(String::toLowerCase).toList();
        assertThat(names).contains("class", "course", "student", "score", "attendance");
        // The e-commerce demo is gone with its dataset.
        assertThat(names).doesNotContain("orders", "products", "customers", "order_items");

        TableSchema score =
                tables.stream().filter(t -> t.name().equalsIgnoreCase("score")).findFirst().orElseThrow();
        assertThat(score.columns())
                .anyMatch(c -> c.name().equalsIgnoreCase("student_id") && !c.nullable());
        assertThat(score.foreignKeys())
                .anyMatch(fk -> fk.referencedTable().equalsIgnoreCase("student"));
    }

    @Test
    void rendersPromptSchemaAsDdl() {
        String ddl = schemaProvider.renderForPrompt("campus", AccessFixtures.admin(), "平均分");
        assertThat(ddl).contains("CREATE TABLE").containsIgnoringCase("student");
    }

    @Test
    void executesReadOnlyAggregate() {
        String sql =
                safetyValidator.validateAndNormalize(
                        "SELECT s.class_id AS class_id, AVG(sc.score) AS avg_score "
                                + "FROM score sc JOIN student s ON s.id = sc.student_id "
                                + "GROUP BY s.class_id ORDER BY class_id");
        QueryResult result = sqlExecutor.executeBlocking(null, sql);
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.columns()).anyMatch(c -> c.name().equalsIgnoreCase("class_id"));
        assertThat(result.columns()).anyMatch(c -> c.name().equalsIgnoreCase("avg_score"));
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void respectsRowLimit() {
        // The demo has 12 attendance rows; asserting a positive bounded row count keeps the test
        // honest without depending on the exact seed size.
        QueryResult result = sqlExecutor.executeBlocking(null, "SELECT * FROM attendance");
        assertThat(result.rowCount()).isBetween(1, 200);
    }
}
