package com.nekocong.text2sql.knowledge;

/**
 * A retrieval hit.
 *
 * @param documentName the source document
 * @param text the chunk text
 * @param score the relevance score (cosine similarity, or lexical overlap in fallback mode)
 */
public record SearchHit(String documentName, String text, double score) {}
