package com.nekocong.text2sql.nl2sql;

/**
 * Domain events emitted by the NL2SQL pipeline. Mapped to SSE event types by the web layer so the
 * frontend never depends on AgentScope or JDBC internals.
 */
public sealed interface Text2SqlEvent permits StepEvent, SqlEvent, RowsEvent, ChartEvent, CitationEvent {}
