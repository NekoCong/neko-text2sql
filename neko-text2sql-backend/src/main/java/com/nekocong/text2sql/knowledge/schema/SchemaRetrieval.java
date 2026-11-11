package com.nekocong.text2sql.knowledge.schema;

import com.nekocong.text2sql.knowledge.Citation;
import com.nekocong.text2sql.knowledge.SearchTrace;
import java.util.List;

/**
 * One retrieval used to ground SQL generation, carrying the schema text and the original-source
 * evidence so the web layer can stream a {@code citations} event.
 *
 * @param schema the prompt-ready schema
 * @param citations the citations of document-backed hits
 * @param trace the explainable retrieval record
 */
public record SchemaRetrieval(String schema, List<Citation> citations, SearchTrace trace) {
}
