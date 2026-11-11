package com.nekocong.text2sql.controller.dto;

/**
 * Request body for a knowledge-base search.
 *
 * @param namespace the namespace (defaults to {@code "default"})
 * @param query the query text
 * @param topK maximum hits (defaults to the configured value)
 */
public record KnowledgeSearchRequest(String namespace, String query, Integer topK) {}
