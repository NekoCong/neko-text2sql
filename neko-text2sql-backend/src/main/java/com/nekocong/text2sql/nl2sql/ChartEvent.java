package com.nekocong.text2sql.nl2sql;

import com.nekocong.text2sql.gbi.ChartSpec;

/**
 * An inferred chart for the result rows (Generative BI).
 *
 * @param chart the chart spec
 */
public record ChartEvent(ChartSpec chart) implements Text2SqlEvent {}
