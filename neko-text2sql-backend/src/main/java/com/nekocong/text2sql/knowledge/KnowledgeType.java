package com.nekocong.text2sql.knowledge;

/** The three knowledge collections. */
public enum KnowledgeType {
    /** Table / column / foreign-key metadata of one datasource. */
    METADATA,
    /** Business terms, metric definitions and field-level semantic mappings. */
    BUSINESS,
    /** Agent-private prompts, tool descriptions and reference documents. */
    AGENT
}
