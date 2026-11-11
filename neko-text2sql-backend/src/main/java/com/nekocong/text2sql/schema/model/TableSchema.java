package com.nekocong.text2sql.schema.model;

import java.util.List;

/**
 * An introspected table: its columns and outbound foreign keys.
 *
 * @param name table name
 * @param columns ordered columns (as declared)
 * @param foreignKeys outbound foreign keys
 */
public record TableSchema(String name, List<ColumnSchema> columns, List<ForeignKeySchema> foreignKeys) {

    /**
     * Returns the primary-key column names.
     *
     * @return the primary-key columns, in declaration order
     */
    public List<String> primaryKeyColumns() {
        return columns.stream().filter(ColumnSchema::primaryKey).map(ColumnSchema::name).toList();
    }
}
