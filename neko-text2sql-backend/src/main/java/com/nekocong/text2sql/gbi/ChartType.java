package com.nekocong.text2sql.gbi;

/** A chart type inferred for a query result (Generative BI). */
public enum ChartType {
    /** No chart is appropriate; render as a table. */
    NONE,
    BAR,
    LINE,
    PIE
}
