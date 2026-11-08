package com.nekocong.text2sql.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.DataScopePolicy;
import com.nekocong.text2sql.config.KnowledgeProperties;
import com.nekocong.text2sql.config.Nl2SqlProperties;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.datasource.DatasourceAccessGuard;
import com.nekocong.text2sql.knowledge.KnowledgeChunk;
import com.nekocong.text2sql.knowledge.KnowledgeDocument;
import com.nekocong.text2sql.knowledge.KnowledgeHit;
import com.nekocong.text2sql.knowledge.KnowledgeItem;
import com.nekocong.text2sql.knowledge.KnowledgeScope;
import com.nekocong.text2sql.knowledge.KnowledgeStatus;
import com.nekocong.text2sql.knowledge.KnowledgeType;
import com.nekocong.text2sql.knowledge.ParseStatus;
import com.nekocong.text2sql.knowledge.SearchResult;
import com.nekocong.text2sql.knowledge.SourceType;
import com.nekocong.text2sql.knowledge.repo.KnowledgeRepository;
import com.nekocong.text2sql.knowledge.store.InMemoryObjectStore;
import com.nekocong.text2sql.knowledge.store.InMemoryVectorStore;
import com.nekocong.text2sql.knowledge.store.VectorStore;
import com.nekocong.text2sql.modelconfig.ModelResolver;
import com.nekocong.text2sql.schema.SchemaIntrospector;
import com.nekocong.text2sql.schema.SchemaRenderer;
import com.nekocong.text2sql.schema.model.ColumnSchema;
import com.nekocong.text2sql.schema.model.ForeignKeySchema;
import com.nekocong.text2sql.schema.model.TableSchema;
import com.nekocong.text2sql.security.AccessFixtures;
import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.security.TablePolicy;
import io.agentscope.core.embedding.EmbeddingModel;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Guards the retrieval chain against in-memory stores: dual recall, citation assembly, the caller's
 * table whitelist, and the full-schema fallback.
 *
 * <p>No embedding model is registered, so the deterministic lexical path runs — the same path a
 * deployment without an embedding key uses.
 */
class KnowledgeSearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final KnowledgeProperties properties = new KnowledgeProperties();
    private final InMemoryObjectStore objectStore = new InMemoryObjectStore();
    private final InMemoryVectorStore vectorStore = new InMemoryVectorStore();
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final DatasourceAccessGuard guard = mock(DatasourceAccessGuard.class);
    private final DataSourceRegistry registry = mock(DataSourceRegistry.class);
    private final SchemaIntrospector introspector = mock(SchemaIntrospector.class);

    private static DataScope admin() {
        return AccessFixtures.admin();
    }

    private KnowledgeSearchService newService() {
        when(guard.resolve(any(), any())).thenReturn("campus");
        when(registry.resolve("campus")).thenReturn(mock(DataSource.class));
        when(introspector.introspect(any())).thenReturn(tables());
        return new KnowledgeSearchService(
                repository,
                vectorStore,
                objectStore,
                new TablePolicy(),
                guard,
                registry,
                introspector,
                new SchemaRenderer(),
                new Nl2SqlProperties(),
                properties,
                emptyProvider(),
                emptyProvider(),
                new ObjectMapper());
    }

    @Test
    void recallsBusinessAndAgentKnowledgeAndAssemblesCitations() {
        KnowledgeSearchService service = newService();
        vectorStore.replaceChunks(
                item("item-b", KnowledgeType.BUSINESS, KnowledgeScope.DATASOURCE, "campus", null,
                        "score", "成绩口径", "成绩低于60分为挂科"),
                List.of(chunk(
                        "c1", "item-b", null, "成绩低于60分为挂科", null, null, 0)));
        KnowledgeDocument document = document("doc-1", "item-a");
        objectStore.put(document.objectKey(), new byte[] {1, 2, 3}, "application/pdf");
        when(repository.findDocument("doc-1")).thenReturn(Optional.of(document));
        vectorStore.replaceChunks(
                item("item-a", KnowledgeType.AGENT, KnowledgeScope.AGENT, null, "agent-1",
                        null, "挂科评分说明", "挂科评分说明"),
                List.of(chunk(
                        "c2", "item-a", "doc-1", "挂科评分说明", 1, new int[] {10, 10, 200, 200}, 0)));

        SearchResult result = service.search(admin(), "campus", "agent-1", "挂科", 5);

        assertThat(result.hits())
                .extracting(KnowledgeHit::knowledgeType)
                .contains(KnowledgeType.BUSINESS, KnowledgeType.AGENT);
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).documentId()).isEqualTo("doc-1");
        assertThat(result.citations().get(0).previewUrl()).startsWith("memory://");
        assertThat(result.citations().get(0).bbox()).containsExactly(10, 10, 200, 200);
        assertThat(result.trace().fallback()).isFalse();
        assertThat(result.trace().finalTables()).contains("score", "student");
    }

    @Test
    void appliesTheCallersTableWhitelist() {
        KnowledgeSearchService service = newService();
        vectorStore.replaceChunks(
                item("item-x", KnowledgeType.BUSINESS, KnowledgeScope.DATASOURCE, "campus", null,
                        "secret_salary", "薪资口径", "薪资统计口径说明"),
                List.of(chunk("c9", "item-x", null, "薪资统计口径说明", null, null, 0)));
        DataScope student = AccessFixtures.student();


        SearchResult result = service.search(student, "campus", null, "薪资", 5);

        assertThat(result.hits()).isEmpty();
        assertThat(result.trace().finalTables()).isEmpty();
        assertThat(result.trace().fallback()).isTrue();
        assertThat(result.trace().fallbackReason()).isNotBlank();
    }

    @Test
    void aScopeWithoutResolvedAccessRecallsNothing() {
        // Fail closed: no access means an empty (not unrestricted) table whitelist.
        DataScope legacy =
                new DataScope("student-1", Roles.STUDENT, Set.of(1L), 5L, "2023001", Set.of("campus"));
        KnowledgeSearchService service = newService();

        SearchResult result = service.search(legacy, "campus", null, "成绩", 5);

        assertThat(result.hits()).isEmpty();
        assertThat(result.trace().candidateTables()).isEmpty();
    }

    @Test
    void aCustomRoleRecallsOnlyItsConfiguredTables() {
        KnowledgeSearchService service = newService();
        vectorStore.replaceChunks(
                item("item-s", KnowledgeType.BUSINESS, KnowledgeScope.DATASOURCE, "campus", null,
                        "score", "成绩口径", "成绩说明"),
                List.of(chunk("c1", "item-s", null, "成绩说明", null, null, 0)));
        vectorStore.replaceChunks(
                item("item-t", KnowledgeType.BUSINESS, KnowledgeScope.DATASOURCE, "campus", null,
                        "teacher_salary", "工资", "教师工资说明"),
                List.of(chunk("c2", "item-t", null, "教师工资说明", null, null, 0)));
        DataScope teacher = AccessFixtures.custom(
                "teacher",
                List.of(AccessFixtures.rule(
                        "t1", "campus", "score", DataScopePolicy.FILTER, "class_id", "classIds")),
                Set.of(1L));

        SearchResult result = service.search(teacher, "campus", null, "成绩", 5);

        assertThat(result.hits()).extracting(KnowledgeHit::content).contains("成绩说明");
        assertThat(result.hits()).extracting(KnowledgeHit::content).doesNotContain("教师工资说明");
        assertThat(result.trace().finalTables()).contains("score").doesNotContain("teacher_salary");
    }

    @Test
    void fallsBackToTheFullSchemaWhenNothingIsRecalled() {
        KnowledgeSearchService service = newService();
        when(repository.listTermMappings("campus")).thenReturn(List.of());

        SearchResult result = service.search(admin(), "campus", null, "完全不相关", 5);

        assertThat(result.hits()).isEmpty();
        assertThat(result.trace().fallback()).isTrue();
        assertThat(result.trace().citations()).isEmpty();
    }

    private static KnowledgeItem item(
            String id,
            KnowledgeType type,
            KnowledgeScope scope,
            String datasourceId,
            String agentId,
            String tableName,
            String title,
            String content) {
        return new KnowledgeItem(
                id,
                type,
                scope,
                datasourceId,
                agentId,
                type + ":" + id,
                title,
                tableName,
                content,
                KnowledgeStatus.READY,
                null,
                null,
                null,
                NOW,
                NOW,
                null);
    }

    private static KnowledgeDocument document(String id, String itemId) {
        return new KnowledgeDocument(
                id,
                itemId,
                SourceType.UPLOAD,
                null,
                "评分说明.pdf",
                "application/pdf",
                100,
                "sha-" + id,
                "originals/" + id + ".pdf",
                "derived/" + id,
                2,
                "mineru",
                "v4",
                ParseStatus.PARSED,
                null,
                null,
                NOW);
    }

    private static KnowledgeChunk chunk(
            String id,
            String itemId,
            String documentId,
            String content,
            Integer pageNo,
            int[] bbox,
            int start) {
        return new KnowledgeChunk(
                id,
                itemId,
                documentId,
                0,
                content,
                pageNo,
                bbox,
                start,
                start + content.length(),
                content,
                null);
    }

    private static List<TableSchema> tables() {
        return List.of(
                new TableSchema(
                        "score",
                        List.of(
                                new ColumnSchema("id", "INT", 0, false, true, "成绩主键"),
                                new ColumnSchema("student_id", "INT", 0, false, false, "学生 id"),
                                new ColumnSchema("score", "DECIMAL", 0, true, false, "成绩")),
                        List.of(new ForeignKeySchema("student_id", "student", "id"))),
                new TableSchema(
                        "student",
                        List.of(
                                new ColumnSchema("id", "INT", 0, false, true, "学生主键"),
                                new ColumnSchema("name", "VARCHAR", 50, true, false, "姓名")),
                        List.of()));
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
