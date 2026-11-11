package com.nekocong.text2sql.access;

import com.nekocong.text2sql.access.api.dto.DatasourceSchemaColumnView;
import com.nekocong.text2sql.access.api.dto.DatasourceSchemaTableView;
import com.nekocong.text2sql.access.api.dto.DatasourceSchemaView;
import com.nekocong.text2sql.config.BlockingSchedulerConfig;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.schema.SchemaIntrospector;
import com.nekocong.text2sql.schema.model.TableSchema;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * The read-only datasource-schema endpoint backing the data-scope editor's table/column selectors.
 *
 * <p>Resolution and introspection are blocking JDBC work, so they run on the blocking scheduler
 * (virtual threads), never on the event loop. The endpoint returns only the table/column names and
 * type names — never connection settings or credentials. An unknown datasource is a {@code 404}; the
 * route itself is gated by {@code access.manage} through the {@code /api/admin/**} rule, like the rest
 * of the access-control surface.
 */
@Service
public class DatasourceSchemaService {

    /** The datasource id space, identical to {@link DataScopeService}'s registry id space. */
    private static final Pattern DATASOURCE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private static final String REASON_DATASOURCE_MISSING = "数据源不存在";

    private final DataSourceRegistry datasources;
    private final SchemaIntrospector introspector;
    private final Scheduler blockingScheduler;

    /**
     * Creates the service.
     *
     * @param datasources the target-datasource registry, to resolve the datasource
     * @param introspector the live metadata reader (blocking JDBC)
     * @param blockingScheduler the scheduler blocking reads are offloaded to
     */
    public DatasourceSchemaService(
            DataSourceRegistry datasources,
            SchemaIntrospector introspector,
            @Qualifier(BlockingSchedulerConfig.BLOCKING_SCHEDULER) Scheduler blockingScheduler) {
        this.datasources = datasources;
        this.introspector = introspector;
        this.blockingScheduler = blockingScheduler;
    }

    /**
     * Reads one datasource's live table/column metadata.
     *
     * @param datasourceId the datasource id
     * @return the schema
     */
    public Mono<DatasourceSchemaView> schema(String datasourceId) {
        return Mono.defer(
                        () -> {
                            requireDatasourceId(datasourceId);
                            return Mono.fromCallable(() -> introspect(datasourceId))
                                    .subscribeOn(blockingScheduler)
                                    .onErrorResume(
                                            NoSuchElementException.class,
                                            error -> Mono.error(notFound()));
                        });
    }

    /** Resolves the datasource and maps its live metadata to the view. */
    private DatasourceSchemaView introspect(String datasourceId) {
        List<TableSchema> tables = introspector.introspect(datasources.resolve(datasourceId));
        List<DatasourceSchemaTableView> viewTables =
                tables.stream()
                        .map(
                                table ->
                                        new DatasourceSchemaTableView(
                                                table.name(),
                                                table.columns().stream()
                                                        .map(
                                                                column ->
                                                                        new DatasourceSchemaColumnView(
                                                                                column.name(),
                                                                                column.typeName()))
                                                        .sorted(
                                                                Comparator.comparing(
                                                                        DatasourceSchemaColumnView::name))
                                                        .toList()))
                        .sorted(Comparator.comparing(DatasourceSchemaTableView::name))
                        .toList();
        return new DatasourceSchemaView(datasourceId, viewTables);
    }

    /** Validates the id against the registry id space (it is only ever bound, never interpolated). */
    private static void requireDatasourceId(String value) {
        if (value == null || !DATASOURCE_ID_PATTERN.matcher(value).matches()) {
            throw badRequest("数据源标识无效");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, REASON_DATASOURCE_MISSING);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
