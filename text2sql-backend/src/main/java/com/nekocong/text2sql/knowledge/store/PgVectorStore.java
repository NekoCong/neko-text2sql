package com.nekocong.text2sql.knowledge.store;

import com.nekocong.text2sql.knowledge.KnowledgeChunk;
import com.nekocong.text2sql.knowledge.KnowledgeItem;
import com.nekocong.text2sql.knowledge.KnowledgeType;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL + pgvector backed {@link VectorStore}.
 *
 * <p>Vectors are written and queried as string literals cast with {@code ?::vector}, so the
 * {@code PGvector} type registration is not required. All methods are blocking.
 */
public class PgVectorStore implements VectorStore {

    private static final String CHUNK_COLUMNS =
            "id, item_id, document_id, chunk_index, content, page_no, bbox::text AS bbox_text, "
                    + "char_start, char_end, quote, embedding::text AS embedding_text";

    private static final String CHUNK_INSERT_SQL =
            "INSERT INTO knowledge_chunk "
                    + "(id, item_id, document_id, chunk_index, content, page_no, bbox, char_start, "
                    + "char_end, quote, embedding) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?::int[], ?, ?, ?, ?::vector)";

    private static final String HIT_SELECT = "SELECT c.id, c.item_id, c.document_id, c.chunk_index, "
            + "c.content, c.page_no, c.bbox::text AS bbox_text, c.char_start, c.char_end, c.quote, "
            + "i.knowledge_type, i.title, i.table_name, ";

    private static final String HIT_FROM =
            " FROM knowledge_chunk c JOIN knowledge_item i ON i.id = c.item_id "
                    + "WHERE i.status = 'READY' AND c.embedding IS NOT NULL";

    private static final RowMapper<VectorHit> HIT_MAPPER = (rs, rowNum) -> new VectorHit(
            rs.getString("id"),
            rs.getString("item_id"),
            enumValue(KnowledgeType.class, rs.getString("knowledge_type")),
            rs.getString("title"),
            rs.getString("table_name"),
            rs.getString("content"),
            rs.getDouble("score"),
            rs.getString("document_id"),
            rs.getObject("page_no", Integer.class),
            parseBbox(rs.getString("bbox_text")),
            rs.getObject("char_start", Integer.class),
            rs.getObject("char_end", Integer.class),
            rs.getString("quote"));

    private static final RowMapper<KnowledgeChunk> CHUNK_MAPPER = (rs, rowNum) -> new KnowledgeChunk(
            rs.getString("id"),
            rs.getString("item_id"),
            rs.getString("document_id"),
            rs.getInt("chunk_index"),
            rs.getString("content"),
            rs.getObject("page_no", Integer.class),
            parseBbox(rs.getString("bbox_text")),
            rs.getObject("char_start", Integer.class),
            rs.getObject("char_end", Integer.class),
            rs.getString("quote"),
            parseVector(rs.getString("embedding_text")));

    private final JdbcTemplate jdbc;

    private final TransactionTemplate transactionTemplate;

    /**
     * Creates the store.
     *
     * @param jdbc the vector database template
     */
    public PgVectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        DataSource dataSource = jdbc.getDataSource();
        this.transactionTemplate = dataSource == null
                ? null
                : new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public void replaceChunks(KnowledgeItem item, List<KnowledgeChunk> chunks) {
        Runnable work = () -> {
            jdbc.update("DELETE FROM knowledge_chunk WHERE item_id = ?", item.id());
            if (!chunks.isEmpty()) {
                jdbc.batchUpdate(CHUNK_INSERT_SQL, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        bindChunk(ps, item, chunks.get(index));
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                });
            }
        };
        try {
            if (transactionTemplate == null) {
                work.run();
            } else {
                transactionTemplate.executeWithoutResult(status -> work.run());
            }
        } catch (VectorStoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new VectorStoreException("向量库不可用：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteByItem(String itemId) {
        try {
            jdbc.update("DELETE FROM knowledge_chunk WHERE item_id = ?", itemId);
        } catch (DataAccessException e) {
            throw new VectorStoreException("向量库不可用：" + e.getMessage(), e);
        }
    }

    @Override
    public List<KnowledgeChunk> listByItem(String itemId) {
        try {
            return jdbc.query(
                    "SELECT " + CHUNK_COLUMNS + " FROM knowledge_chunk WHERE item_id = ? ORDER BY chunk_index",
                    CHUNK_MAPPER,
                    itemId);
        } catch (DataAccessException e) {
            throw new VectorStoreException("向量库不可用：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<KnowledgeChunk> findChunk(String chunkId) {
        try {
            return jdbc.query(
                            "SELECT " + CHUNK_COLUMNS + " FROM knowledge_chunk WHERE id = ?",
                            CHUNK_MAPPER,
                            chunkId)
                    .stream()
                    .findFirst();
        } catch (DataAccessException e) {
            throw new VectorStoreException("向量库不可用：" + e.getMessage(), e);
        }
    }

    @Override
    public List<VectorHit> search(double[] queryVector, VectorFilter filter, int topK) {
        String literal = vectorLiteral(queryVector);
        String sql = HIT_SELECT
                + "1 - (c.embedding <=> ?::vector) AS score"
                + HIT_FROM
                + filterClause(filter)
                + " ORDER BY c.embedding <=> ?::vector LIMIT ?";
        PreparedStatementCreator creator = connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            int index = 1;
            ps.setObject(index++, literal, Types.OTHER);
            index = bindFilter(ps, index, filter, connection);
            ps.setObject(index++, literal, Types.OTHER);
            ps.setInt(index, Math.max(1, topK));
            return ps;
        };
        try {
            return jdbc.query(creator, HIT_MAPPER);
        } catch (DataAccessException e) {
            throw new VectorStoreException("向量库不可用：" + e.getMessage(), e);
        }
    }

    @Override
    public List<VectorHit> searchLexical(String query, VectorFilter filter, int topK) {
        String pattern = "%" + (query == null ? "" : query) + "%";
        String sql = HIT_SELECT
                + "0.0 AS score"
                + HIT_FROM
                + filterClause(filter)
                + " AND (c.content ILIKE ? OR coalesce(c.quote, '') ILIKE ?)";
        PreparedStatementCreator creator = connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            int index = bindFilter(ps, 1, filter, connection);
            ps.setString(index++, pattern);
            ps.setString(index, pattern);
            return ps;
        };
        List<VectorHit> hits;
        try {
            hits = jdbc.query(creator, HIT_MAPPER);
        } catch (DataAccessException e) {
            throw new VectorStoreException("向量库不可用：" + e.getMessage(), e);
        }
        return hits.stream()
                .map(hit -> withScore(hit, lexical(query, hit.content())))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(VectorHit::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    private static void bindChunk(PreparedStatement ps, KnowledgeItem item, KnowledgeChunk chunk)
            throws SQLException {
        ps.setString(1, chunk.id() == null ? UUID.randomUUID().toString() : chunk.id());
        ps.setString(2, chunk.itemId() == null ? item.id() : chunk.itemId());
        ps.setString(3, chunk.documentId());
        ps.setInt(4, chunk.chunkIndex());
        ps.setString(5, chunk.content());
        setNullableInt(ps, 6, chunk.pageNo());
        setLiteral(ps, 7, bboxLiteral(chunk.bbox()));
        setNullableInt(ps, 8, chunk.charStart());
        setNullableInt(ps, 9, chunk.charEnd());
        ps.setString(10, chunk.quote());
        setLiteral(ps, 11, vectorLiteral(chunk.embedding()));
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static void setLiteral(PreparedStatement ps, int index, String literal) throws SQLException {
        if (literal == null) {
            ps.setNull(index, Types.OTHER);
        } else {
            ps.setObject(index, literal, Types.OTHER);
        }
    }

    private static String filterClause(VectorFilter filter) {
        StringBuilder sql = new StringBuilder(" AND i.knowledge_type = ?");
        if (filter.datasourceId() != null) {
            sql.append(" AND (i.datasource_id = ? OR i.datasource_id IS NULL)");
        }
        if (filter.agentId() != null) {
            sql.append(" AND i.agent_id = ?");
        }
        if (filter.allowedTables() != null) {
            sql.append(" AND i.table_name = ANY(?)");
        }
        if (filter.allowedItemIds() != null) {
            sql.append(" AND i.id = ANY(?)");
        }
        return sql.toString();
    }

    private static int bindFilter(
            PreparedStatement ps, int index, VectorFilter filter, Connection connection) throws SQLException {
        ps.setString(index++, filter.knowledgeType().name());
        if (filter.datasourceId() != null) {
            ps.setString(index++, filter.datasourceId());
        }
        if (filter.agentId() != null) {
            ps.setString(index++, filter.agentId());
        }
        if (filter.allowedTables() != null) {
            Array array = connection.createArrayOf("varchar", filter.allowedTables().toArray());
            ps.setArray(index++, array);
        }
        if (filter.allowedItemIds() != null) {
            Array array = connection.createArrayOf("varchar", filter.allowedItemIds().toArray());
            ps.setArray(index++, array);
        }
        return index;
    }

    private static String vectorLiteral(double[] vector) {
        if (vector == null) {
            return null;
        }
        StringBuilder literal = new StringBuilder(vector.length * 12 + 2);
        literal.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }

    private static String bboxLiteral(int[] bbox) {
        if (bbox == null) {
            return null;
        }
        StringBuilder literal = new StringBuilder(bbox.length * 6 + 2);
        literal.append('{');
        for (int i = 0; i < bbox.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(bbox[i]);
        }
        return literal.append('}').toString();
    }

    private static int[] parseBbox(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String body = text.strip();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return null;
        }
        String[] parts = body.split(",");
        int[] bbox = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            bbox[i] = Integer.parseInt(parts[i].strip());
        }
        return bbox;
    }

    private static double[] parseVector(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String body = text.strip();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return null;
        }
        String[] parts = body.split(",");
        double[] vector = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Double.parseDouble(parts[i].strip());
        }
        return vector;
    }

    private static VectorHit withScore(VectorHit hit, double score) {
        return new VectorHit(
                hit.chunkId(),
                hit.itemId(),
                hit.knowledgeType(),
                hit.title(),
                hit.tableName(),
                hit.content(),
                score,
                hit.documentId(),
                hit.pageNo(),
                hit.bbox(),
                hit.charStart(),
                hit.charEnd(),
                hit.quote());
    }

    private static double lexical(String query, String text) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        String body = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return 0;
        }
        if (body.contains(q)) {
            return 1.0;
        }
        Set<String> queryTokens = tokens(q);
        if (queryTokens.isEmpty()) {
            return 0;
        }
        Set<String> textTokens = tokens(body);
        long overlap = queryTokens.stream().filter(textTokens::contains).count();
        return (double) overlap / queryTokens.size();
    }

    private static Set<String> tokens(String value) {
        return java.util.Arrays.stream(value.split("[^a-z0-9\\u4e00-\\u9fff]+"))
                .filter(token -> token.length() >= 2)
                .collect(Collectors.toSet());
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
