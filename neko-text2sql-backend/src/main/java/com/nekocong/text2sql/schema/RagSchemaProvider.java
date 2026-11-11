package com.nekocong.text2sql.schema;

import com.nekocong.text2sql.config.Nl2SqlProperties;
import com.nekocong.text2sql.config.RagProperties;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.schema.model.ColumnSchema;
import com.nekocong.text2sql.schema.model.ForeignKeySchema;
import com.nekocong.text2sql.schema.model.TableSchema;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.TablePolicy;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Embedding-based schema retrieval (DB-GPT's {@code DBSchemaRetriever} idea, simplified).
 *
 * <p>Each table is summarised as text (name + columns), embedded once and cached per datasource.
 * For a question, the top-k most similar tables are selected, then expanded with their
 * foreign-key neighbours (join reachability — the classic failure mode of pure top-k retrieval),
 * and finally rendered as DDL within the prompt budget.
 *
 * <p>Fails safe: on any embedding error, or when no table clears the score threshold, it falls back
 * to the whole schema. Active only when {@code text2sql.rag.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "text2sql.rag", name = "enabled", havingValue = "true")
public class RagSchemaProvider implements SchemaProvider {

    private final EmbeddingModel embeddingModel;
    private final DataSourceRegistry registry;
    private final SchemaIntrospector introspector;
    private final SchemaRenderer renderer;
    private final RagProperties ragProperties;
    private final Nl2SqlProperties nl2SqlProperties;
    private final TablePolicy policy;
    private final Map<String, List<IndexedTable>> indexCache = new ConcurrentHashMap<>();

    /**
     * Creates the provider.
     *
     * @param embeddingModel the embedding model
     * @param registry the datasource registry
     * @param introspector the schema introspector
     * @param renderer the DDL renderer
     * @param ragProperties the RAG configuration
     * @param nl2SqlProperties the pipeline limits (used for the render budget)
     * @param policy the table policy used to hide out-of-scope tables
     */
    public RagSchemaProvider(
            EmbeddingModel embeddingModel,
            DataSourceRegistry registry,
            SchemaIntrospector introspector,
            SchemaRenderer renderer,
            RagProperties ragProperties,
            Nl2SqlProperties nl2SqlProperties,
            TablePolicy policy) {
        this.embeddingModel = embeddingModel;
        this.registry = registry;
        this.introspector = introspector;
        this.renderer = renderer;
        this.ragProperties = ragProperties;
        this.nl2SqlProperties = nl2SqlProperties;
        this.policy = policy;
    }

    @Override
    public String renderForPrompt(String datasourceId, DataScope scope, String question) {
        DataSource dataSource = registry.resolve(datasourceId);
        List<TableSchema> tables =
                introspector.introspect(dataSource).stream()
                        .filter(table -> policy.allows(scope, datasourceId, table.name()))
                        .toList();
        if (tables.isEmpty()) {
            return "";
        }
        try {
            List<TableSchema> selected = selectRelevant(tables, question);
            return render(selected, tables);
        } catch (RuntimeException ex) {
            // Fail safe: never block NL2SQL because retrieval failed.
            return render(tables, tables);
        }
    }

    private List<TableSchema> selectRelevant(List<TableSchema> tables, String question) {
        List<IndexedTable> index = indexFor(tables);
        double[] query = embed(question);
        if (query == null) {
            return tables;
        }
        List<ScoredTable> scored = new ArrayList<>();
        for (IndexedTable indexed : index) {
            scored.add(new ScoredTable(indexed.table(), cosine(query, indexed.vector())));
        }
        scored.sort(Comparator.comparingDouble(ScoredTable::score).reversed());

        List<TableSchema> top = new ArrayList<>();
        for (ScoredTable candidate : scored) {
            if (top.size() >= ragProperties.getTopK()) {
                break;
            }
            if (candidate.score() >= ragProperties.getScoreThreshold()) {
                top.add(candidate.table());
            }
        }
        if (top.isEmpty()) {
            return tables; // nothing cleared the threshold -> don't starve the model
        }
        return expandWithNeighbours(top, tables);
    }

    private List<TableSchema> expandWithNeighbours(List<TableSchema> selected, List<TableSchema> all) {
        Set<String> names = new LinkedHashSet<>();
        selected.forEach(table -> names.add(table.name().toLowerCase()));

        for (TableSchema table : selected) {
            for (ForeignKeySchema fk : table.foreignKeys()) {
                names.add(fk.referencedTable().toLowerCase());
            }
        }
        for (TableSchema table : all) {
            for (ForeignKeySchema fk : table.foreignKeys()) {
                if (names.contains(fk.referencedTable().toLowerCase())) {
                    names.add(table.name().toLowerCase());
                }
            }
        }
        List<TableSchema> result = new ArrayList<>();
        for (TableSchema table : all) {
            if (names.contains(table.name().toLowerCase())) {
                result.add(table);
            }
        }
        return result;
    }

    private String render(List<TableSchema> selected, List<TableSchema> all) {
        int budget = nl2SqlProperties.getSchemaCharBudget();
        StringBuilder body = new StringBuilder();
        int omitted = 0;
        for (TableSchema table : selected) {
            String ddl = renderer.renderTable(table);
            if (budget > 0 && body.length() + ddl.length() > budget && body.length() > 0) {
                omitted++;
                continue;
            }
            body.append(ddl);
        }
        if (omitted > 0) {
            body.append("\n-- ").append(omitted).append(" further table(s) omitted for brevity\n");
        }
        return body + renderer.renderRelationships(selected);
    }

    private List<IndexedTable> indexFor(List<TableSchema> tables) {
        String key = tables.stream().map(TableSchema::name).reduce("", (a, b) -> a + "|" + b);
        return indexCache.computeIfAbsent(
                key,
                ignored -> {
                    List<IndexedTable> indexed = new ArrayList<>();
                    for (TableSchema table : tables) {
                        double[] vector = embed(summarize(table));
                        if (vector != null) {
                            indexed.add(new IndexedTable(table, vector));
                        }
                    }
                    return indexed;
                });
    }

    private static String summarize(TableSchema table) {
        StringBuilder sb = new StringBuilder(table.name());
        for (ColumnSchema column : table.columns()) {
            sb.append(' ').append(column.name());
        }
        return sb.toString();
    }

    private double[] embed(String text) {
        return embeddingModel.embed(TextBlock.builder().text(text).build()).block();
    }

    private static double cosine(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record IndexedTable(TableSchema table, double[] vector) {}

    private record ScoredTable(TableSchema table, double score) {}
}
