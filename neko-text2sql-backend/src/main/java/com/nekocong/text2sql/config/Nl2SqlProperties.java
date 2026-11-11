package com.nekocong.text2sql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Safety / cost limits for the NL2SQL pipeline, bound from {@code text2sql.nl2sql.*}.
 */
@ConfigurationProperties(prefix = "text2sql.nl2sql")
public class Nl2SqlProperties {

    /** Maximum number of rows returned to the UI per query. */
    private int rowLimit = 200;

    /** Per-statement query timeout, in seconds. */
    private int timeoutSeconds = 15;

    /**
     * Approximate character budget for the schema injected into the prompt. When the full schema
     * exceeds it, tables are included until the budget is reached (Phase 2 replaces this with
     * embedding retrieval; the interface is unchanged).
     */
    private int schemaCharBudget = 3000;

    public int getRowLimit() {
        return rowLimit;
    }

    public void setRowLimit(int rowLimit) {
        this.rowLimit = rowLimit;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getSchemaCharBudget() {
        return schemaCharBudget;
    }

    public void setSchemaCharBudget(int schemaCharBudget) {
        this.schemaCharBudget = schemaCharBudget;
    }
}
