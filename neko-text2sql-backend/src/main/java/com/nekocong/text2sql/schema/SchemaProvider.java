package com.nekocong.text2sql.schema;

import com.nekocong.text2sql.security.DataScope;

/**
 * Supplies the database schema (as prompt-ready text) used to ground SQL generation.
 *
 * <p>Phase 1 ships {@link FullSchemaProvider} (whole small schema). Phase 2 adds a
 * retrieval/embedding implementation behind this same interface, so the pipeline never changes.
 *
 * <p>Every implementation must only render objects inside the caller's data scope: the model must
 * never be told about a table the caller cannot query.
 */
public interface SchemaProvider {

    /**
     * Renders the schema that should ground SQL generation for a question.
     *
     * @param datasourceId the datasource id (or {@code null} for the default)
     * @param scope the caller's data scope
     * @param question the natural-language question
     * @return prompt-ready schema text
     */
    String renderForPrompt(String datasourceId, DataScope scope, String question);
}
