package com.nekocong.text2sql.knowledge.schema;

import com.nekocong.text2sql.security.DataScope;

/**
 * A {@code SchemaProvider} that can also return the retrieval evidence behind its schema.
 *
 * <p>Kept separate from {@link com.nekocong.text2sql.schema.SchemaProvider} so the pipeline's
 * long-standing contract stays unchanged while the web layer can still stream citations.
 */
public interface CitationAwareSchemaProvider {

    /**
     * Retrieves the schema and its evidence for one question.
     *
     * @param datasourceId the datasource id
     * @param scope the caller's data scope
     * @param question the natural-language question
     * @return the schema plus citations and trace
     */
    SchemaRetrieval retrieveForPrompt(String datasourceId, DataScope scope, String question);
}
