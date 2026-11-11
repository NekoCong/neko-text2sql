package com.nekocong.text2sql.controller.dto;

/**
 * Request body for knowledge-base document ingestion.
 *
 * @param namespace the namespace (defaults to {@code "default"})
 * @param name the document name
 * @param content the document text
 */
public record KnowledgeDocumentRequest(String namespace, String name, String content) {}
