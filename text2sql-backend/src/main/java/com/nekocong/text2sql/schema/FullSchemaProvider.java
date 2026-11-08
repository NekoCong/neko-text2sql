package com.nekocong.text2sql.schema;

import com.nekocong.text2sql.config.Nl2SqlProperties;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.schema.model.TableSchema;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.TablePolicy;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Renders the <em>entire</em> datasource schema as compact DDL, honouring a character budget.
 *
 * <p>Appropriate for the small demo schema. When the rendered schema would exceed
 * {@code text2sql.nl2sql.schema-char-budget}, tables are included until the budget is reached and
 * the remainder is summarised — a crude degradation that Phase 2 replaces with retrieval.
 *
 * <p>Active by default; when {@code text2sql.rag.enabled=true} the embedding-based
 * {@link RagSchemaProvider} takes over instead.
 */
@Component
@ConditionalOnProperty(
        prefix = "text2sql.rag",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public class FullSchemaProvider implements SchemaProvider {

    private static final Logger log = LoggerFactory.getLogger(FullSchemaProvider.class);

    private final DataSourceRegistry registry;
    private final SchemaIntrospector introspector;
    private final SchemaRenderer renderer;
    private final Nl2SqlProperties properties;
    private final TablePolicy policy;

    /**
     * Creates the provider.
     *
     * @param registry the datasource registry
     * @param introspector the JDBC schema introspector
     * @param renderer the DDL renderer
     * @param properties the NL2SQL limits
     * @param policy the table policy used to hide out-of-scope tables
     */
    public FullSchemaProvider(
            DataSourceRegistry registry,
            SchemaIntrospector introspector,
            SchemaRenderer renderer,
            Nl2SqlProperties properties,
            TablePolicy policy) {
        this.registry = registry;
        this.introspector = introspector;
        this.renderer = renderer;
        this.properties = properties;
        this.policy = policy;
    }

    @Override
    public String renderForPrompt(String datasourceId, DataScope scope, String question) {
        DataSource dataSource = registry.resolve(datasourceId);
        List<TableSchema> tables =
                introspector.introspect(dataSource).stream()
                        .filter(table -> policy.allows(scope, datasourceId, table.name()))
                        .toList();
        return render(tables);
    }

    private String render(List<TableSchema> tables) {
        int budget = properties.getSchemaCharBudget();
        StringBuilder body = new StringBuilder();
        int omitted = 0;
        for (TableSchema table : tables) {
            String ddl = renderer.renderTable(table);
            if (budget > 0 && body.length() + ddl.length() > budget && body.length() > 0) {
                omitted++;
                continue;
            }
            body.append(ddl);
        }
        if (omitted > 0) {
            log.warn(
                    "Schema budget ({} chars) exceeded: {} table(s) omitted from the prompt",
                    budget,
                    omitted);
            body.append("\n-- ").append(omitted).append(" further table(s) omitted for brevity\n");
        }
        return body + renderer.renderRelationships(tables);
    }
}
