package com.nekocong.text2sql.schema;

import com.nekocong.text2sql.schema.model.ColumnSchema;
import com.nekocong.text2sql.schema.model.ForeignKeySchema;
import com.nekocong.text2sql.schema.model.TableSchema;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders introspected tables as compact, prompt-ready DDL.
 */
@Component
public class SchemaRenderer {

    /**
     * Renders a single table as DDL.
     *
     * @param table the table
     * @return the DDL text
     */
    public String renderTable(TableSchema table) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(table.name()).append(" (\n");
        List<String> parts = new ArrayList<>();
        for (ColumnSchema column : table.columns()) {
            StringBuilder line = new StringBuilder();
            line.append("  ").append(column.name()).append(' ').append(column.typeDisplay());
            if (!column.nullable()) {
                line.append(" NOT NULL");
            }
            parts.add(line.toString());
        }
        List<String> primaryKeys = table.primaryKeyColumns();
        if (!primaryKeys.isEmpty()) {
            parts.add("  PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
        }
        sb.append(String.join(",\n", parts)).append("\n);\n");
        return sb.toString();
    }

    /**
     * Renders the foreign-key relationships across tables.
     *
     * @param tables the tables
     * @return the relationship comments, or an empty string when there are none
     */
    public String renderRelationships(List<TableSchema> tables) {
        List<String> relationships = new ArrayList<>();
        for (TableSchema table : tables) {
            for (ForeignKeySchema fk : table.foreignKeys()) {
                relationships.add(
                        "-- "
                                + table.name()
                                + "."
                                + fk.column()
                                + " -> "
                                + fk.referencedTable()
                                + "."
                                + fk.referencedColumn());
            }
        }
        if (relationships.isEmpty()) {
            return "";
        }
        return "\n-- Relationships\n" + String.join("\n", relationships) + "\n";
    }
}
