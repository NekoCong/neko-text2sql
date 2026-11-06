package com.nekocong.text2sql.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.config.KnowledgeProperties;
import com.nekocong.text2sql.config.Nl2SqlProperties;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.datasource.DatasourceAccessGuard;
import com.nekocong.text2sql.knowledge.Citation;
import com.nekocong.text2sql.knowledge.KnowledgeDocument;
import com.nekocong.text2sql.knowledge.KnowledgeHit;
import com.nekocong.text2sql.knowledge.KnowledgeType;
import com.nekocong.text2sql.knowledge.SearchResult;
import com.nekocong.text2sql.knowledge.SearchTrace;
import com.nekocong.text2sql.knowledge.TermMapping;
import com.nekocong.text2sql.knowledge.repo.KnowledgeRepository;
import com.nekocong.text2sql.knowledge.schema.SchemaRetrieval;
import com.nekocong.text2sql.knowledge.store.ObjectStore;
import com.nekocong.text2sql.knowledge.store.ObjectStoreException;
import com.nekocong.text2sql.knowledge.store.VectorFilter;
import com.nekocong.text2sql.knowledge.store.VectorHit;
import com.nekocong.text2sql.knowledge.store.VectorStore;
import com.nekocong.text2sql.modelconfig.ModelResolver;
import com.nekocong.text2sql.schema.SchemaIntrospector;
import com.nekocong.text2sql.schema.SchemaRenderer;
import com.nekocong.text2sql.schema.model.ForeignKeySchema;
import com.nekocong.text2sql.schema.model.TableSchema;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.TablePolicy;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The explainable retrieval chain: rewrite → dual recall → table/column expansion → FK hop → LLM
 * selection → scoped rendering, with citations and a deterministic full-schema fallback.
 *
 * <p>Scope trimming happens <em>before</em> recall: the vector filter carries the caller's datasource
 * and table whitelist, so out-of-scope rows never reach the application.
 */
@Service
@ConditionalOnProperty(prefix = "text2sql.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);

    private final KnowledgeRepository repository;
    private final VectorStore vectorStore;
    private final ObjectStore objectStore;
    private final TablePolicy tablePolicy;
    private final DatasourceAccessGuard datasourceGuard;
    private final DataSourceRegistry registry;
    private final SchemaIntrospector introspector;
    private final SchemaRenderer renderer;
    private final Nl2SqlProperties nl2SqlProperties;
    private final KnowledgeProperties properties;
    private final ObjectProvider<EmbeddingModel> embeddings;
    private final ObjectProvider<ModelResolver> modelResolver;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service.
     *
     * @param repository the knowledge repository
     * @param vectorStore the chunk/vector store
     * @param objectStore the object store (for preview URLs)
     * @param tablePolicy the table whitelist
     * @param datasourceGuard the datasource authorization guard
     * @param registry the datasource registry
     * @param introspector the schema introspector
     * @param renderer the DDL renderer
     * @param nl2SqlProperties the prompt budget
     * @param properties the knowledge configuration
     * @param embeddings the optional embedding model
     * @param modelResolver the per-user model resolver (LLM table selection)
     * @param objectMapper the shared Jackson mapper
     */
    public KnowledgeSearchService(
            KnowledgeRepository repository,
            VectorStore vectorStore,
            ObjectStore objectStore,
            TablePolicy tablePolicy,
            DatasourceAccessGuard datasourceGuard,
            DataSourceRegistry registry,
            SchemaIntrospector introspector,
            SchemaRenderer renderer,
            Nl2SqlProperties nl2SqlProperties,
            KnowledgeProperties properties,
            ObjectProvider<EmbeddingModel> embeddings,
            ObjectProvider<ModelResolver> modelResolver,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.objectStore = objectStore;
        this.tablePolicy = tablePolicy;
        this.datasourceGuard = datasourceGuard;
        this.registry = registry;
        this.introspector = introspector;
        this.renderer = renderer;
        this.nl2SqlProperties = nl2SqlProperties;
        this.properties = properties;
        this.embeddings = embeddings;
        this.modelResolver = modelResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Searches the knowledge collections inside the caller's scope.
     *
     * @param scope the caller's scope
     * @param datasourceId the requested datasource (or {@code null} for the default)
     * @param agentId the agent whose private knowledge may be used, or {@code null}
     * @param question the question
     * @param topK maximum hits per route, or {@code null}
     * @return the hits, citations and trace
     */
    public SearchResult search(
            DataScope scope, String datasourceId, String agentId, String question, Integer topK) {
        return execute(scope, datasourceId, agentId, question, topK).result();
    }

    /**
     * Retrieves the schema used to ground SQL generation, plus its evidence.
     *
     * @param datasourceId the datasource id
     * @param scope the caller's scope
     * @param question the question
     * @return the schema, citations and trace
     */
    public SchemaRetrieval retrieveForPrompt(String datasourceId, DataScope scope, String question) {
        Outcome outcome = execute(scope, datasourceId, null, question, null);
        return new SchemaRetrieval(outcome.schema(), outcome.result().citations(), outcome.result().trace());
    }

    private Outcome execute(
            DataScope scope, String datasourceId, String agentId, String question, Integer topK) {
        String id = datasourceGuard.resolve(scope, datasourceId);
        String query = question == null ? "" : question.strip();
        int limit = (topK == null || topK <= 0) ? properties.getTopK() : topK;
        Set<String> allowedTables = scope.isAdmin() ? null : tablePolicy.tablesFor(scope.role());
        String rewritten = rewrite(id, query);

        List<VectorHit> business = List.of();
        List<VectorHit> agent = List.of();
        List<VectorHit> metadata = List.of();
        String failure = null;
        double[] vector = embed(rewritten);
        try {
            VectorFilter businessFilter =
                    new VectorFilter(KnowledgeType.BUSINESS, id, null, allowedTables, null);
            business = recall(vector, rewritten, businessFilter, limit);
        } catch (RuntimeException ex) {
            failure = "业务知识召回失败：" + readable(ex);
            log.warn(failure);
        }
        if (agentId != null && !agentId.isBlank()) {
            try {
                VectorFilter agentFilter =
                        new VectorFilter(KnowledgeType.AGENT, null, agentId, null, null);
                agent = recall(vector, rewritten, agentFilter, limit);
            } catch (RuntimeException ex) {
                failure = failure == null ? "智能体知识召回失败：" + readable(ex) : failure;
                log.warn("智能体知识召回失败：{}", readable(ex));
            }
        }
        try {
            VectorFilter metadataFilter = new VectorFilter(
                    KnowledgeType.METADATA, id, null, allowedTables, null);
            metadata = recall(vector, rewritten, metadataFilter, properties.getCandidateTables());
        } catch (RuntimeException ex) {
            failure = failure == null ? "元数据召回失败：" + readable(ex) : failure;
            log.warn("元数据召回失败：{}", readable(ex));
        }

        List<String> candidateTables = candidateTables(business, metadata, allowedTables);
        List<String> expanded = expandForeignKeys(id, candidateTables, allowedTables);
        List<String> finalTables = selectTables(scope.userId(), query, expanded);
        List<Citation> citations = new ArrayList<>();
        List<KnowledgeHit> hits = new ArrayList<>();
        hits.addAll(toHits(business, citations));
        hits.addAll(toHits(agent, citations));
        hits.addAll(toHits(metadata, citations));
        hits.sort(Comparator.comparingDouble(KnowledgeHit::score).reversed());

        String schema;
        boolean fallback = false;
        String fallbackReason = null;
        try {
            schema = renderSchema(id, finalTables);
        } catch (RuntimeException ex) {
            fallback = true;
            fallbackReason = "schema 渲染失败：" + readable(ex);
            schema = "";
        }
        if (schema.isBlank()) {
            fallback = true;
            if (fallbackReason == null) {
                fallbackReason = failure == null ? "未召回任何表，回退全量 schema" : failure;
            }
            schema = renderFull(id, scope);
        }

        SearchTrace trace = new SearchTrace(
                rewritten,
                business.size(),
                agent.size(),
                candidateTables,
                finalTables,
                fallback,
                fallbackReason,
                List.copyOf(citations));
        return new Outcome(
                schema, new SearchResult(List.copyOf(hits), List.copyOf(citations), trace));
    }

    private List<VectorHit> recall(
            double[] vector, String query, VectorFilter filter, int limit) {
        return vector == null
                ? vectorStore.searchLexical(query, filter, limit)
                : vectorStore.search(vector, filter, limit);
    }

    private List<String> candidateTables(
            List<VectorHit> business, List<VectorHit> metadata, Set<String> allowedTables) {
        Set<String> tables = new LinkedHashSet<>();
        for (VectorHit hit : metadata) {
            addTable(tables, hit.tableName(), allowedTables);
        }
        for (VectorHit hit : business) {
            addTable(tables, hit.tableName(), allowedTables);
        }
        return List.copyOf(tables);
    }

    private static void addTable(Set<String> tables, String table, Set<String> allowed) {
        if (table == null || table.isBlank()) {
            return;
        }
        String name = table.toLowerCase(Locale.ROOT);
        if (allowed == null || allowed.contains(name)) {
            tables.add(name);
        }
    }

    /**
     * Adds one foreign-key hop in both directions, then re-applies the whitelist so a JOIN neighbour
     * outside the caller's scope is never surfaced.
     */
    private List<String> expandForeignKeys(
            String datasourceId, List<String> candidates, Set<String> allowedTables) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<TableSchema> tables = introspector.introspect(registry.resolve(datasourceId));
        Set<String> expanded = new LinkedHashSet<>(candidates);
        Set<String> candidateSet = new LinkedHashSet<>(candidates);
        for (TableSchema table : tables) {
            String name = table.name().toLowerCase(Locale.ROOT);
            boolean isCandidate = candidateSet.contains(name);
            for (ForeignKeySchema fk : table.foreignKeys()) {
                String referenced = fk.referencedTable() == null
                        ? null
                        : fk.referencedTable().toLowerCase(Locale.ROOT);
                if (isCandidate && referenced != null) {
                    addTable(expanded, referenced, allowedTables);
                }
                if (referenced != null && candidateSet.contains(referenced)) {
                    addTable(expanded, name, allowedTables);
                }
            }
        }
        return List.copyOf(expanded);
    }

    private List<String> selectTables(String userId, String question, List<String> candidates) {
        if (candidates.size() <= properties.getLlmSelectThreshold()
                || !properties.isLlmSelectEnabled()) {
            return candidates;
        }
        try {
            ModelResolver resolver = modelResolver.getIfAvailable();
            if (resolver == null) {
                return candidates;
            }
            Model model = resolver.resolve(userId).block(Duration.ofMillis(properties.getLlmSelectTimeoutMs()));
            if (model == null) {
                return candidates;
            }
            String prompt = "候选表："
                    + String.join(", ", candidates)
                    + "\n问题："
                    + question
                    + "\n只输出一个 JSON 字符串数组，元素必须来自候选表；不确定时输出候选表本身。";
            GenerateOptions options = GenerateOptions.builder().temperature(0.0).build();
            String answer = model
                    .stream(
                            List.of(
                                    new SystemMessage("你是数据库表选择器，只输出 JSON，不要解释。"),
                                    new UserMessage(prompt)),
                            List.of(),
                            options)
                    .map(KnowledgeSearchService::textOf)
                    .collect(StringBuilder::new, StringBuilder::append)
                    .block(Duration.ofMillis(properties.getLlmSelectTimeoutMs()))
                    .toString();
            List<String> selected = parseSelection(answer, candidates);
            return selected.isEmpty() ? candidates : selected;
        } catch (RuntimeException ex) {
            log.warn("LLM 选表失败，回退规则候选集：{}", readable(ex));
            return candidates;
        }
    }

    private List<String> parseSelection(String answer, List<String> candidates) {
        try {
            int start = answer.indexOf('[');
            int end = answer.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return List.of();
            }
            JsonNode node = objectMapper.readTree(answer.substring(start, end + 1));
            if (!node.isArray()) {
                return List.of();
            }
            Map<String, String> canonical = new LinkedHashMap<>();
            for (String candidate : candidates) {
                canonical.put(candidate.toLowerCase(Locale.ROOT), candidate);
            }
            List<String> selected = new ArrayList<>();
            for (JsonNode element : node) {
                String match = canonical.get(element.asText().toLowerCase(Locale.ROOT));
                if (match != null && !selected.contains(match)) {
                    selected.add(match);
                }
            }
            return selected;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String renderSchema(String datasourceId, List<String> finalTables) {
        if (finalTables.isEmpty()) {
            return "";
        }
        Map<String, TableSchema> byName = new LinkedHashMap<>();
        for (TableSchema table : introspector.introspect(registry.resolve(datasourceId))) {
            byName.put(table.name().toLowerCase(Locale.ROOT), table);
        }
        List<TableSchema> selected = finalTables.stream()
                .map(byName::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        return renderWithinBudget(selected);
    }

    private String renderFull(String datasourceId, DataScope scope) {
        List<TableSchema> tables = introspector.introspect(registry.resolve(datasourceId)).stream()
                .filter(table -> tablePolicy.allows(scope, table.name()))
                .toList();
        return renderWithinBudget(tables);
    }

    private String renderWithinBudget(List<TableSchema> tables) {
        int budget = nl2SqlProperties.getSchemaCharBudget();
        StringBuilder body = new StringBuilder();
        List<TableSchema> included = new ArrayList<>();
        int omitted = 0;
        for (TableSchema table : tables) {
            String ddl = renderer.renderTable(table);
            if (budget > 0 && body.length() > 0 && body.length() + ddl.length() > budget) {
                omitted++;
                continue;
            }
            body.append(ddl);
            included.add(table);
        }
        if (omitted > 0) {
            body.append("\n-- ").append(omitted).append(" further table(s) omitted for brevity\n");
        }
        return body + renderer.renderRelationships(included);
    }

    private String rewrite(String datasourceId, String question) {
        List<TermMapping> mappings = repository.listTermMappings(datasourceId);
        if (mappings.isEmpty() || question.isBlank()) {
            return question;
        }
        StringBuilder expanded = new StringBuilder(question);
        for (TermMapping mapping : mappings) {
            if (mapping.term() == null || !question.contains(mapping.term())) {
                continue;
            }
            expanded.append(' ')
                    .append(mapping.term())
                    .append(" -> ")
                    .append(mapping.tableName());
            if (mapping.columnName() != null && !mapping.columnName().isBlank()) {
                expanded.append('.').append(mapping.columnName());
            }
            if (mapping.description() != null && !mapping.description().isBlank()) {
                expanded.append(' ').append(mapping.description());
            }
        }
        return expanded.toString();
    }

    private double[] embed(String text) {
        EmbeddingModel model = embeddings.getIfAvailable();
        if (model == null) {
            return null;
        }
        try {
            return model.embed(TextBlock.builder().text(text).build()).block();
        } catch (RuntimeException ex) {
            log.warn("查询向量生成失败，改用词法召回：{}", readable(ex));
            return null;
        }
    }

    private List<KnowledgeHit> toHits(List<VectorHit> hits, List<Citation> citations) {
        List<KnowledgeHit> result = new ArrayList<>(hits.size());
        for (VectorHit hit : hits) {
            Citation citation = citation(hit);
            if (citation != null) {
                citations.add(citation);
            }
            result.add(new KnowledgeHit(
                    hit.itemId(),
                    hit.knowledgeType(),
                    hit.title(),
                    hit.content(),
                    hit.score(),
                    citation));
        }
        return result;
    }

    private Citation citation(VectorHit hit) {
        if (hit.documentId() == null) {
            return null;
        }
        Optional<KnowledgeDocument> maybe = repository.findDocument(hit.documentId());
        if (maybe.isEmpty()) {
            return null;
        }
        KnowledgeDocument document = maybe.get();
        String previewUrl = null;
        Instant expiresAt = null;
        try {
            Duration ttl = Duration.ofSeconds(properties.getMinio().getPresignTtlSeconds());
            previewUrl = objectStore.presignGet(document.objectKey(), ttl);
            expiresAt = Instant.now().plus(ttl);
        } catch (ObjectStoreException ex) {
            log.warn("原文不可预览（仍返回引用文本）：{}", ex.getMessage());
        }
        return new Citation(
                document.id(),
                document.filename(),
                document.mimeType(),
                document.sourceType().name(),
                hit.pageNo(),
                hit.bbox() == null
                        ? null
                        : java.util.Arrays.stream(hit.bbox()).boxed().toList(),
                hit.charStart(),
                hit.charEnd(),
                hit.quote(),
                previewUrl,
                expiresAt,
                document.fetchedAt());
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                sb.append(textBlock.getText());
            }
        }
        return sb.toString();
    }

    private static String readable(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private record Outcome(String schema, SearchResult result) {
    }
}
