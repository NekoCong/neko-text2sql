package com.nekocong.text2sql.knowledge.repo;

import com.nekocong.text2sql.knowledge.FailureStage;
import com.nekocong.text2sql.knowledge.KnowledgeDocument;
import com.nekocong.text2sql.knowledge.KnowledgeItem;
import com.nekocong.text2sql.knowledge.KnowledgeScope;
import com.nekocong.text2sql.knowledge.KnowledgeStatus;
import com.nekocong.text2sql.knowledge.KnowledgeType;
import com.nekocong.text2sql.knowledge.ParseStatus;
import com.nekocong.text2sql.knowledge.SourceType;
import com.nekocong.text2sql.knowledge.TermMapping;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * PostgreSQL-backed {@link KnowledgeRepository}.
 *
 * <p>Uses plain {@link JdbcTemplate} statements against the tables defined in
 * {@code db/knowledge-schema.sql}. All methods are blocking.
 */
public class JdbcKnowledgeRepository implements KnowledgeRepository {

    private static final String ITEM_COLUMNS =
            "id, knowledge_type, scope, datasource_id, agent_id, item_key, title, table_name, content, "
                    + "status, failure_stage, failure_reason, embedding_model, created_at, updated_at";

    private static final String ITEM_UPSERT_SQL =
            "INSERT INTO knowledge_item ("
                    + ITEM_COLUMNS
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (item_key) DO UPDATE SET "
                    + "knowledge_type = EXCLUDED.knowledge_type, "
                    + "scope = EXCLUDED.scope, "
                    + "datasource_id = EXCLUDED.datasource_id, "
                    + "agent_id = EXCLUDED.agent_id, "
                    + "title = EXCLUDED.title, "
                    + "table_name = EXCLUDED.table_name, "
                    + "content = EXCLUDED.content, "
                    + "status = EXCLUDED.status, "
                    + "failure_stage = EXCLUDED.failure_stage, "
                    + "failure_reason = EXCLUDED.failure_reason, "
                    + "embedding_model = EXCLUDED.embedding_model, "
                    + "updated_at = now(), "
                    + "version = knowledge_item.version + 1 "
                    + "RETURNING "
                    + ITEM_COLUMNS;

    private static final String DOCUMENT_COLUMNS =
            "id, item_id, source_type, source_url, filename, mime_type, size_bytes, sha256, object_key, "
                    + "derived_prefix, page_count, parse_engine, parse_engine_version, parse_status, "
                    + "parse_failure_reason, fetched_at, created_at";

    private static final String DOCUMENT_UPSERT_SQL =
            "INSERT INTO knowledge_document ("
                    + DOCUMENT_COLUMNS
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (item_id) DO UPDATE SET "
                    + "source_type = EXCLUDED.source_type, "
                    + "source_url = EXCLUDED.source_url, "
                    + "filename = EXCLUDED.filename, "
                    + "mime_type = EXCLUDED.mime_type, "
                    + "size_bytes = EXCLUDED.size_bytes, "
                    + "sha256 = EXCLUDED.sha256, "
                    + "object_key = EXCLUDED.object_key, "
                    + "derived_prefix = EXCLUDED.derived_prefix, "
                    + "page_count = EXCLUDED.page_count, "
                    + "parse_engine = EXCLUDED.parse_engine, "
                    + "parse_engine_version = EXCLUDED.parse_engine_version, "
                    + "parse_status = EXCLUDED.parse_status, "
                    + "parse_failure_reason = EXCLUDED.parse_failure_reason, "
                    + "fetched_at = EXCLUDED.fetched_at "
                    + "RETURNING "
                    + DOCUMENT_COLUMNS;

    private static final String TERM_COLUMNS =
            "id, datasource_id, term, target_type, table_name, column_name, description";

    private static final String TERM_UPSERT_SQL =
            "INSERT INTO knowledge_term_mapping ("
                    + TERM_COLUMNS
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (datasource_id, term) DO UPDATE SET "
                    + "target_type = EXCLUDED.target_type, "
                    + "table_name = EXCLUDED.table_name, "
                    + "column_name = EXCLUDED.column_name, "
                    + "description = EXCLUDED.description "
                    + "RETURNING "
                    + TERM_COLUMNS;

    private static final RowMapper<KnowledgeItem> ITEM_MAPPER = (rs, rowNum) -> new KnowledgeItem(
            rs.getString("id"),
            enumValue(KnowledgeType.class, rs.getString("knowledge_type")),
            enumValue(KnowledgeScope.class, rs.getString("scope")),
            rs.getString("datasource_id"),
            rs.getString("agent_id"),
            rs.getString("item_key"),
            rs.getString("title"),
            rs.getString("table_name"),
            rs.getString("content"),
            enumValue(KnowledgeStatus.class, rs.getString("status")),
            enumValue(FailureStage.class, rs.getString("failure_stage")),
            rs.getString("failure_reason"),
            rs.getString("embedding_model"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            null);

    private static final RowMapper<KnowledgeDocument> DOCUMENT_MAPPER =
            (rs, rowNum) -> new KnowledgeDocument(
                    rs.getString("id"),
                    rs.getString("item_id"),
                    enumValue(SourceType.class, rs.getString("source_type")),
                    rs.getString("source_url"),
                    rs.getString("filename"),
                    rs.getString("mime_type"),
                    rs.getLong("size_bytes"),
                    rs.getString("sha256"),
                    rs.getString("object_key"),
                    rs.getString("derived_prefix"),
                    rs.getObject("page_count", Integer.class),
                    rs.getString("parse_engine"),
                    rs.getString("parse_engine_version"),
                    enumValue(ParseStatus.class, rs.getString("parse_status")),
                    rs.getString("parse_failure_reason"),
                    instant(rs, "fetched_at"),
                    instant(rs, "created_at"));

    private static final RowMapper<TermMapping> TERM_MAPPER = (rs, rowNum) -> new TermMapping(
            rs.getString("id"),
            rs.getString("datasource_id"),
            rs.getString("term"),
            rs.getString("target_type"),
            rs.getString("table_name"),
            rs.getString("column_name"),
            rs.getString("description"));

    private final JdbcTemplate jdbc;

    /**
     * Creates the repository.
     *
     * @param jdbc the knowledge database template
     */
    public JdbcKnowledgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public KnowledgeItem upsertItem(KnowledgeItem item) {
        String id = item.id() == null ? UUID.randomUUID().toString() : item.id();
        Instant now = Instant.now();
        return jdbc.queryForObject(
                ITEM_UPSERT_SQL,
                ITEM_MAPPER,
                id,
                enumName(item.knowledgeType()),
                enumName(item.scope()),
                item.datasourceId(),
                item.agentId(),
                item.itemKey(),
                item.title(),
                item.tableName(),
                item.content(),
                enumName(item.status()),
                enumName(item.failureStage()),
                item.failureReason(),
                item.embeddingModel(),
                Timestamp.from(item.createdAt() == null ? now : item.createdAt()),
                Timestamp.from(item.updatedAt() == null ? now : item.updatedAt()));
    }

    @Override
    public Optional<KnowledgeItem> findItem(String id) {
        List<KnowledgeItem> rows = jdbc.query(
                "SELECT " + ITEM_COLUMNS + " FROM knowledge_item WHERE id = ?", ITEM_MAPPER, id);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<KnowledgeItem> findItemByKey(String itemKey) {
        List<KnowledgeItem> rows = jdbc.query(
                "SELECT " + ITEM_COLUMNS + " FROM knowledge_item WHERE item_key = ?", ITEM_MAPPER, itemKey);
        return rows.stream().findFirst();
    }

    @Override
    public List<KnowledgeItem> listItems(
            KnowledgeType type, String datasourceId, String agentId, KnowledgeStatus status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + ITEM_COLUMNS + " FROM knowledge_item WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (type != null) {
            sql.append(" AND knowledge_type = ?");
            args.add(type.name());
        }
        if (datasourceId != null) {
            sql.append(" AND datasource_id = ?");
            args.add(datasourceId);
        }
        if (agentId != null) {
            sql.append(" AND agent_id = ?");
            args.add(agentId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbc.query(sql.toString(), ITEM_MAPPER, args.toArray());
    }

    @Override
    public void updateItemStatus(
            String id, KnowledgeStatus status, String failureStage, String reason, String embeddingModel) {
        jdbc.update(
                "UPDATE knowledge_item SET status = ?, failure_stage = ?, failure_reason = ?, "
                        + "embedding_model = COALESCE(?, embedding_model), updated_at = now() WHERE id = ?",
                enumName(status),
                failureStage,
                reason,
                embeddingModel,
                id);
    }

    @Override
    public void deleteItem(String id) {
        jdbc.update("DELETE FROM knowledge_item WHERE id = ?", id);
    }

    @Override
    public KnowledgeDocument upsertDocument(KnowledgeDocument document) {
        String id = document.id() == null ? UUID.randomUUID().toString() : document.id();
        Timestamp createdAt = Timestamp.from(
                document.createdAt() == null ? Instant.now() : document.createdAt());
        return jdbc.queryForObject(
                DOCUMENT_UPSERT_SQL,
                DOCUMENT_MAPPER,
                id,
                document.itemId(),
                enumName(document.sourceType()),
                document.sourceUrl(),
                document.filename(),
                document.mimeType(),
                document.sizeBytes(),
                document.sha256(),
                document.objectKey(),
                document.derivedPrefix(),
                document.pageCount(),
                document.parseEngine(),
                document.parseEngineVersion(),
                enumName(document.parseStatus()),
                document.parseFailureReason(),
                timestamp(document.fetchedAt()),
                createdAt);
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(String id) {
        List<KnowledgeDocument> rows = jdbc.query(
                "SELECT " + DOCUMENT_COLUMNS + " FROM knowledge_document WHERE id = ?",
                DOCUMENT_MAPPER,
                id);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<KnowledgeDocument> findDocumentByItem(String itemId) {
        List<KnowledgeDocument> rows = jdbc.query(
                "SELECT " + DOCUMENT_COLUMNS + " FROM knowledge_document WHERE item_id = ?",
                DOCUMENT_MAPPER,
                itemId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<KnowledgeDocument> findDocumentBySha(String sha256) {
        List<KnowledgeDocument> rows = jdbc.query(
                "SELECT " + DOCUMENT_COLUMNS
                        + " FROM knowledge_document WHERE sha256 = ? ORDER BY created_at DESC LIMIT 1",
                DOCUMENT_MAPPER,
                sha256);
        return rows.stream().findFirst();
    }

    @Override
    public List<KnowledgeDocument> listDocuments(
            KnowledgeType type, String datasourceId, String agentId, KnowledgeStatus status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT d.id, d.item_id, d.source_type, d.source_url, "
                + "d.filename, d.mime_type, d.size_bytes, d.sha256, d.object_key, d.derived_prefix, "
                + "d.page_count, d.parse_engine, d.parse_engine_version, d.parse_status, "
                + "d.parse_failure_reason, d.fetched_at, d.created_at "
                + "FROM knowledge_document d JOIN knowledge_item i ON i.id = d.item_id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (type != null) {
            sql.append(" AND i.knowledge_type = ?");
            args.add(type.name());
        }
        if (datasourceId != null) {
            sql.append(" AND i.datasource_id = ?");
            args.add(datasourceId);
        }
        if (agentId != null) {
            sql.append(" AND i.agent_id = ?");
            args.add(agentId);
        }
        if (status != null) {
            sql.append(" AND i.status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY d.created_at DESC LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbc.query(sql.toString(), DOCUMENT_MAPPER, args.toArray());
    }

    @Override
    public void updateDocumentParse(
            String id, ParseStatus status, String engine, String version, Integer pageCount, String reason) {
        jdbc.update(
                "UPDATE knowledge_document SET parse_status = ?, parse_engine = ?, "
                        + "parse_engine_version = ?, page_count = ?, parse_failure_reason = ? WHERE id = ?",
                enumName(status),
                engine,
                version,
                pageCount,
                reason,
                id);
    }

    @Override
    public void deleteDocument(String id) {
        jdbc.update("DELETE FROM knowledge_document WHERE id = ?", id);
    }

    @Override
    public List<TermMapping> listTermMappings(String datasourceId) {
        return jdbc.query(
                "SELECT " + TERM_COLUMNS
                        + " FROM knowledge_term_mapping WHERE datasource_id = ? ORDER BY term",
                TERM_MAPPER,
                datasourceId);
    }

    @Override
    public TermMapping upsertTermMapping(TermMapping mapping) {
        String id = mapping.id() == null ? UUID.randomUUID().toString() : mapping.id();
        return jdbc.queryForObject(
                TERM_UPSERT_SQL,
                TERM_MAPPER,
                id,
                mapping.datasourceId(),
                mapping.term(),
                mapping.targetType(),
                mapping.tableName(),
                mapping.columnName(),
                mapping.description());
    }

    @Override
    public void deleteTermMapping(String id) {
        jdbc.update("DELETE FROM knowledge_term_mapping WHERE id = ?", id);
    }

    @Override
    public List<KnowledgeItem> findStaleItems(Instant olderThan, int limit) {
        return jdbc.query(
                "SELECT " + ITEM_COLUMNS
                        + " FROM knowledge_item WHERE status IN ('PENDING', 'PARSING', 'EMBEDDING') "
                        + "AND updated_at < ? ORDER BY updated_at ASC LIMIT ?",
                ITEM_MAPPER,
                Timestamp.from(olderThan),
                Math.max(1, limit));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
