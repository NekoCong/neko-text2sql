package com.nekocong.text2sql.sql;

import com.nekocong.text2sql.config.Nl2SqlProperties;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.sql.model.ColumnMeta;
import com.nekocong.text2sql.sql.model.QueryResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Executes validated read-only SQL against a datasource.
 *
 * <p>Blocking JDBC is offloaded to {@link Schedulers#boundedElastic()} so the WebFlux event loop is
 * never blocked. Safety is enforced here as well as in {@link SqlSafetyValidator}: read-only
 * connection, row cap and per-statement timeout.
 */
@Component
public class SqlExecutor {

    private final DataSourceRegistry registry;
    private final Nl2SqlProperties properties;

    /**
     * Creates the executor.
     *
     * @param registry the datasource registry
     * @param properties the NL2SQL limits (row cap, timeout)
     */
    public SqlExecutor(DataSourceRegistry registry, Nl2SqlProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * Executes a read-only query.
     *
     * @param datasourceId the datasource id (or {@code null} for the default)
     * @param sql the validated SQL
     * @return a mono emitting the query result, computed on a bounded-elastic scheduler
     */
    public Mono<QueryResult> execute(String datasourceId, String sql) {
        return Mono.fromCallable(() -> executeBlocking(datasourceId, sql))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Blocking execution. Prefer {@link #execute(String, String)}; call directly only from
     * non-web threads (tools, tests).
     *
     * @param datasourceId the datasource id (or {@code null} for the default)
     * @param sql the validated SQL
     * @return the query result
     */
    public QueryResult executeBlocking(String datasourceId, String sql) {
        DataSource dataSource = registry.resolve(datasourceId);
        int rowLimit = properties.getRowLimit();
        long start = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(properties.getTimeoutSeconds());
                statement.setMaxRows(rowLimit + 1);
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    ResultSetMetaData meta = resultSet.getMetaData();
                    int columnCount = meta.getColumnCount();
                    List<ColumnMeta> columns = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        columns.add(new ColumnMeta(label(meta, i), meta.getColumnTypeName(i)));
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (resultSet.next() && rows.size() < rowLimit) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(columns.get(i - 1).name(), resultSet.getObject(i));
                        }
                        rows.add(row);
                    }
                    boolean truncated = resultSet.next();
                    long elapsed = System.currentTimeMillis() - start;
                    return new QueryResult(columns, rows, rows.size(), truncated, elapsed);
                }
            }
        } catch (SQLException ex) {
            throw new SqlExecutionException("Query execution failed: " + ex.getMessage(), ex);
        }
    }

    private static String label(ResultSetMetaData meta, int index) throws SQLException {
        String label = meta.getColumnLabel(index);
        return (label == null || label.isBlank()) ? meta.getColumnName(index) : label;
    }
}
