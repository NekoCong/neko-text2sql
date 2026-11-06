package com.nekocong.text2sql.knowledge;

import java.util.List;

/**
 * A retrieval result with the evidence and an explainable trace.
 *
 * @param hits the merged hits
 * @param citations the citations of document-backed hits, in hit order
 * @param trace the retrieval trace
 */
public record SearchResult(List<KnowledgeHit> hits, List<Citation> citations, SearchTrace trace) {
}
