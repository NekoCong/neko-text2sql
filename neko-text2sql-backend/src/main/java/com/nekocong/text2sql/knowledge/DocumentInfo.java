package com.nekocong.text2sql.knowledge;

/**
 * A document ingested into the knowledge base.
 *
 * @param namespace the namespace it belongs to
 * @param name the document name
 * @param chunks the number of chunks it was split into
 */
public record DocumentInfo(String namespace, String name, int chunks) {}
