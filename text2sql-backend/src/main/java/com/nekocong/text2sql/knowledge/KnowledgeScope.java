package com.nekocong.text2sql.knowledge;

/** The isolation dimension a knowledge item belongs to. */
public enum KnowledgeScope {
    /** Shared by every datasource. */
    GLOBAL,
    /** Bound to one datasource. */
    DATASOURCE,
    /** Bound to one agent. */
    AGENT
}
