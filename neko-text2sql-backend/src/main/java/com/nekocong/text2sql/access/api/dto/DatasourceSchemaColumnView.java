package com.nekocong.text2sql.access.api.dto;

/**
 * One column of a datasource's live metadata, as returned to the data-scope editor.
 *
 * <p>Deliberately minimal: only the name and the introspected type name. The type name is what the
 * data-scope editor needs to pair a column with a principal attribute; connection secrets and other
 * datasource details are never returned.
 *
 * @param name the column name
 * @param typeName the database-specific type name (e.g. {@code BIGINT}, {@code VARCHAR})
 */
public record DatasourceSchemaColumnView(String name, String typeName) {}
