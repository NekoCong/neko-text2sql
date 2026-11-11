package com.nekocong.text2sql.schema.model;

/**
 * A single database column.
 *
 * @param name column name
 * @param typeName database-specific type name (e.g. {@code VARCHAR})
 * @param size declared size, or {@code 0} when not applicable
 * @param nullable whether NULL is permitted
 * @param primaryKey whether the column participates in the primary key
 * @param remarks column comment, or {@code null}
 */
public record ColumnSchema(
        String name,
        String typeName,
        int size,
        boolean nullable,
        boolean primaryKey,
        String remarks) {

    /**
     * Renders the column's type with its size when meaningful.
     *
     * @return the type (e.g. {@code VARCHAR(100)})
     */
    public String typeDisplay() {
        if (size > 0 && needsSize(typeName)) {
            return typeName + "(" + size + ")";
        }
        return typeName;
    }

    private static boolean needsSize(String typeName) {
        if (typeName == null) {
            return false;
        }
        String upper = typeName.toUpperCase();
        return upper.contains("CHAR") || upper.contains("VARCHAR") || upper.contains("DECIMAL")
                || upper.contains("NUMERIC");
    }
}
