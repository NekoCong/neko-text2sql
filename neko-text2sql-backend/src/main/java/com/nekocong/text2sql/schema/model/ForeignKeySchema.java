package com.nekocong.text2sql.schema.model;

/**
 * A foreign-key relationship from a local column to a referenced table/column.
 *
 * @param column the referencing column
 * @param referencedTable the referenced table
 * @param referencedColumn the referenced column
 */
public record ForeignKeySchema(String column, String referencedTable, String referencedColumn) {}
