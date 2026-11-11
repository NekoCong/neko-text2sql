package com.nekocong.text2sql.knowledge.service;

import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.knowledge.KnowledgeItem;
import com.nekocong.text2sql.knowledge.KnowledgeScope;
import com.nekocong.text2sql.knowledge.KnowledgeStatus;
import com.nekocong.text2sql.knowledge.KnowledgeType;
import com.nekocong.text2sql.knowledge.repo.KnowledgeRepository;
import com.nekocong.text2sql.knowledge.store.VectorStore;
import com.nekocong.text2sql.schema.SchemaIntrospector;
import com.nekocong.text2sql.schema.model.ColumnSchema;
import com.nekocong.text2sql.schema.model.ForeignKeySchema;
import com.nekocong.text2sql.schema.model.TableSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Syncs a datasource's live schema into the metadata knowledge collection.
 *
 * <p>One item per table, keyed by {@code METADATA:{datasourceId}:{table}}, so a re-sync updates the
 * existing item (and replaces its vectors) instead of duplicating it. Tables that disappeared from
 * the database are removed.
 */
@Service
@ConditionalOnProperty(prefix = "text2sql.knowledge", name = "enabled", havingValue = "true")
public class MetadataSyncService {

    private final DataSourceRegistry registry;
    private final SchemaIntrospector introspector;
    private final KnowledgeRepository repository;
    private final VectorStore vectorStore;
    private final ApplicationEventPublisher events;

    /**
     * Creates the service.
     *
     * @param registry the datasource registry
     * @param introspector the JDBC schema introspector
     * @param repository the knowledge repository
     * @param vectorStore the chunk/vector store
     * @param events the event publisher
     */
    public MetadataSyncService(
            DataSourceRegistry registry,
            SchemaIntrospector introspector,
            KnowledgeRepository repository,
            VectorStore vectorStore,
            ApplicationEventPublisher events) {
        this.registry = registry;
        this.introspector = introspector;
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.events = events;
    }

    /**
     * Introspects a datasource and upserts its metadata items.
     *
     * @param datasourceId the datasource id
     * @return the sync summary
     */
    @Transactional("knowledgeTransactionManager")
    public MetadataSyncResult sync(String datasourceId) {
        List<TableSchema> tables = introspector.introspect(registry.resolve(datasourceId));
        Instant now = Instant.now();
        List<String> itemIds = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (TableSchema table : tables) {
            present.add(table.name().toLowerCase(java.util.Locale.ROOT));
            String itemKey = "METADATA:" + datasourceId + ":" + table.name();
            KnowledgeItem item = new KnowledgeItem(
                    UUID.randomUUID().toString(),
                    KnowledgeType.METADATA,
                    KnowledgeScope.DATASOURCE,
                    datasourceId,
                    null,
                    itemKey,
                    table.name(),
                    table.name(),
                    render(table),
                    KnowledgeStatus.PENDING,
                    null,
                    null,
                    null,
                    now,
                    now,
                    null);
            KnowledgeItem stored = repository.upsertItem(item);
            itemIds.add(stored.id());
            events.publishEvent(KnowledgeItemSavedEvent.full(stored.id()));
        }

        for (KnowledgeItem existing : repository.listItems(
                KnowledgeType.METADATA, datasourceId, null, null, 10_000)) {
            if (existing.tableName() == null
                    || !present.contains(existing.tableName().toLowerCase(java.util.Locale.ROOT))) {
                vectorStore.deleteByItem(existing.id());
                repository.deleteItem(existing.id());
            }
        }
        return new MetadataSyncResult(datasourceId, tables.size(), itemIds);
    }

    /**
     * Renders a table as indexable metadata text: name, columns with semantics, keys and foreign keys.
     *
     * @param table the table
     * @return the text
     */
    static String render(TableSchema table) {
        StringBuilder sb = new StringBuilder();
        sb.append("表 ").append(table.name()).append('\n');
        sb.append("字段：\n");
        for (ColumnSchema column : table.columns()) {
            sb.append("- ").append(column.name()).append(' ').append(column.typeDisplay());
            if (column.primaryKey()) {
                sb.append(" 主键");
            }
            if (!column.nullable()) {
                sb.append(" 非空");
            }
            if (column.remarks() != null && !column.remarks().isBlank()) {
                sb.append(" 语义：").append(column.remarks().strip());
            }
            sb.append('\n');
        }
        if (!table.foreignKeys().isEmpty()) {
            sb.append("外键：\n");
            for (ForeignKeySchema fk : table.foreignKeys()) {
                sb.append("- ")
                        .append(fk.column())
                        .append(" -> ")
                        .append(fk.referencedTable())
                        .append('.')
                        .append(fk.referencedColumn())
                        .append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * The result of one metadata sync.
     *
     * @param datasourceId the datasource
     * @param tableCount the number of tables synced
     * @param itemIds the upserted item ids
     */
    public record MetadataSyncResult(String datasourceId, int tableCount, List<String> itemIds) {
    }
}
