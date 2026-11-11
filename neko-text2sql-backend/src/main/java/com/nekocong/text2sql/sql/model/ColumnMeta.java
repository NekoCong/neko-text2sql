package com.nekocong.text2sql.sql.model;

/**
 * Metadata for one result column.
 *
 * @param name the column label (alias when present)
 * @param typeName the database-specific type name
 */
public record ColumnMeta(String name, String typeName) {}
