package com.nekocong.text2sql.schema;

import com.nekocong.text2sql.schema.model.ColumnSchema;
import com.nekocong.text2sql.schema.model.ForeignKeySchema;
import com.nekocong.text2sql.schema.model.TableSchema;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads relational schema out of a datasource via JDBC {@link DatabaseMetaData}.
 *
 * <p>This is the single source of truth for the NL2SQL prompt — the hand-written
 * {@code knowledge/SCHEMA.md} is intentionally not used, so the prompt can never drift from the
 * live database. Blocking JDBC: callers must invoke this on a bounded-elastic scheduler.
 */
@Component
public class SchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(SchemaIntrospector.class);

    /** Schemas/catalogs that must never be surfaced to the model. */
    private static final Set<String> SYSTEM_SCHEMAS =
            Set.of("information_schema", "pg_catalog", "sys", "mysql", "performance_schema");

    /**
     * Introspects all user tables of a datasource.
     *
     * @param dataSource the datasource
     * @return the tables, sorted by name
     */
    public List<TableSchema> introspect(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = safeSchema(connection);
            List<TableSchema> tables = new ArrayList<>();
            try (ResultSet tableRows = metadata.getTables(catalog, schema, "%", new String[] {"TABLE"})) {
                while (tableRows.next()) {
                    String tableName = tableRows.getString("TABLE_NAME");
                    String tableSchema = tableRows.getString("TABLE_SCHEM");
                    if (isSystemTable(tableName, tableSchema)) {
                        continue;
                    }
                    tables.add(readTable(metadata, catalog, schema, tableName));
                }
            }
            tables.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            return tables;
        } catch (SQLException ex) {
            throw new SchemaIntrospectionException("Failed to introspect schema", ex);
        }
    }

    private TableSchema readTable(
            DatabaseMetaData metadata, String catalog, String schema, String tableName)
            throws SQLException {
        Set<String> primaryKeys = readPrimaryKeys(metadata, catalog, schema, tableName);
        List<ColumnSchema> columns = new ArrayList<>();
        try (ResultSet columnRows = metadata.getColumns(catalog, schema, tableName, "%")) {
            while (columnRows.next()) {
                String name = columnRows.getString("COLUMN_NAME");
                columns.add(
                        new ColumnSchema(
                                name,
                                columnRows.getString("TYPE_NAME"),
                                columnRows.getInt("COLUMN_SIZE"),
                                columnRows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                                primaryKeys.contains(name.toUpperCase(Locale.ROOT)),
                                columnRows.getString("REMARKS")));
            }
        }
        return new TableSchema(tableName, columns, readForeignKeys(metadata, catalog, schema, tableName));
    }

    private Set<String> readPrimaryKeys(
            DatabaseMetaData metadata, String catalog, String schema, String tableName)
            throws SQLException {
        Set<String> keys = new LinkedHashSet<>();
        try (ResultSet pkRows = metadata.getPrimaryKeys(catalog, schema, tableName)) {
            while (pkRows.next()) {
                keys.add(pkRows.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
            }
        }
        return keys;
    }

    private List<ForeignKeySchema> readForeignKeys(
            DatabaseMetaData metadata, String catalog, String schema, String tableName)
            throws SQLException {
        List<ForeignKeySchema> foreignKeys = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (ResultSet fkRows = metadata.getImportedKeys(catalog, schema, tableName)) {
            while (fkRows.next()) {
                String column = fkRows.getString("FKCOLUMN_NAME");
                String referencedTable = fkRows.getString("PKTABLE_NAME");
                String referencedColumn = fkRows.getString("PKCOLUMN_NAME");
                if (seen.add(column + "->" + referencedTable + "." + referencedColumn)) {
                    foreignKeys.add(new ForeignKeySchema(column, referencedTable, referencedColumn));
                }
            }
        }
        return foreignKeys;
    }

    private static String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError ex) {
            log.debug("Datasource does not expose schema(); using null");
            return null;
        }
    }

    private static boolean isSystemTable(String tableName, String tableSchema) {
        if (tableSchema != null && SYSTEM_SCHEMAS.contains(tableSchema.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return tableName == null || tableName.toUpperCase(Locale.ROOT).startsWith("SYS");
    }
}
