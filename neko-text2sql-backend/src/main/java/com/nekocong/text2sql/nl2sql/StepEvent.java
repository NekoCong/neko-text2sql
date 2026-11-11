package com.nekocong.text2sql.nl2sql;

/**
 * A single step in the NL2SQL pipeline, used only for progress reporting.
 *
 * @param phase short machine-readable phase id (e.g. {@code introspect}, {@code generate})
 * @param detail human-readable description of the step
 */
public record StepEvent(String phase, String detail) implements Text2SqlEvent {}
