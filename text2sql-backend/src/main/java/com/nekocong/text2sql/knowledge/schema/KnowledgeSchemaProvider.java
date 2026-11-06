package com.nekocong.text2sql.knowledge.schema;

import com.nekocong.text2sql.knowledge.service.KnowledgeSearchService;
import com.nekocong.text2sql.schema.SchemaProvider;
import com.nekocong.text2sql.security.DataScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The persistent-knowledge schema provider: retrieval-grounded schema plus citation evidence.
 *
 * <p>{@code @Primary} so it wins over {@link com.nekocong.text2sql.schema.FullSchemaProvider} and
 * {@code RagSchemaProvider} whenever the knowledge base is enabled; when it is disabled this bean is
 * absent and the previous provider stays in charge (a one-flag rollback).
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "text2sql.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeSchemaProvider implements SchemaProvider, CitationAwareSchemaProvider {

    private final KnowledgeSearchService search;

    /**
     * Creates the provider.
     *
     * @param search the retrieval service
     */
    public KnowledgeSchemaProvider(KnowledgeSearchService search) {
        this.search = search;
    }

    @Override
    public String renderForPrompt(String datasourceId, DataScope scope, String question) {
        return search.retrieveForPrompt(datasourceId, scope, question).schema();
    }

    @Override
    public SchemaRetrieval retrieveForPrompt(
            String datasourceId, DataScope scope, String question) {
        return search.retrieveForPrompt(datasourceId, scope, question);
    }
}
