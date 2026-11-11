package com.nekocong.text2sql.knowledge.store;

import com.nekocong.text2sql.knowledge.KnowledgeChunk;
import com.nekocong.text2sql.knowledge.KnowledgeItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory {@link VectorStore} for unit tests (and for running without pgvector).
 *
 * <p>Mirrors the SQL filter semantics exactly: type, datasource/global, agent, table whitelist and
 * explicit item allow-list are applied before scoring.
 */
public class InMemoryVectorStore implements VectorStore {

    private final Map<String, KnowledgeItem> items = new ConcurrentHashMap<>();
    private final Map<String, List<KnowledgeChunk>> chunks = new ConcurrentHashMap<>();

    @Override
    public void replaceChunks(KnowledgeItem item, List<KnowledgeChunk> value) {
        items.put(item.id(), item);
        chunks.put(item.id(), List.copyOf(value));
    }

    @Override
    public void deleteByItem(String itemId) {
        items.remove(itemId);
        chunks.remove(itemId);
    }

    @Override
    public List<KnowledgeChunk> listByItem(String itemId) {
        return chunks.getOrDefault(itemId, List.of()).stream()
                .sorted(Comparator.comparingInt(KnowledgeChunk::chunkIndex))
                .toList();
    }

    @Override
    public Optional<KnowledgeChunk> findChunk(String chunkId) {
        return chunks.values().stream()
                .flatMap(List::stream)
                .filter(chunk -> chunk.id().equals(chunkId))
                .findFirst();
    }

    @Override
    public List<VectorHit> search(double[] queryVector, VectorFilter filter, int topK) {
        List<Scored> scored = new ArrayList<>();
        forEachVisible(filter, (item, chunk) -> {
            if (chunk.embedding() == null) {
                return;
            }
            scored.add(new Scored(item, chunk, cosine(queryVector, chunk.embedding())));
        });
        return top(scored, topK);
    }

    @Override
    public List<VectorHit> searchLexical(String query, VectorFilter filter, int topK) {
        List<Scored> scored = new ArrayList<>();
        forEachVisible(filter, (item, chunk) -> {
            double score = lexical(query, chunk.content());
            if (score > 0) {
                scored.add(new Scored(item, chunk, score));
            }
        });
        return top(scored, topK);
    }

    private void forEachVisible(VectorFilter filter, java.util.function.BiConsumer<KnowledgeItem, KnowledgeChunk> sink) {
        for (Map.Entry<String, List<KnowledgeChunk>> entry : chunks.entrySet()) {
            KnowledgeItem item = items.get(entry.getKey());
            if (item == null || !visible(item, filter)) {
                continue;
            }
            for (KnowledgeChunk chunk : entry.getValue()) {
                sink.accept(item, chunk);
            }
        }
    }

    private static boolean visible(KnowledgeItem item, VectorFilter filter) {
        if (item.knowledgeType() != filter.knowledgeType()) {
            return false;
        }
        if (filter.allowedItemIds() != null && !filter.allowedItemIds().contains(item.id())) {
            return false;
        }
        if (filter.allowedTables() != null
                && item.tableName() != null
                && !filter.allowedTables().contains(item.tableName().toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (filter.agentId() != null && !filter.agentId().equals(item.agentId())) {
            return false;
        }
        if (filter.datasourceId() != null
                && item.datasourceId() != null
                && !filter.datasourceId().equals(item.datasourceId())) {
            return false;
        }
        return true;
    }

    private static List<VectorHit> top(List<Scored> scored, int topK) {
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(Math.max(1, topK))
                .map(Scored::toHit)
                .toList();
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

    private static double cosine(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record Scored(KnowledgeItem item, KnowledgeChunk chunk, double score) {

        VectorHit toHit() {
            return new VectorHit(
                    chunk.id(),
                    item.id(),
                    item.knowledgeType(),
                    item.title(),
                    item.tableName(),
                    chunk.content(),
                    score,
                    chunk.documentId(),
                    chunk.pageNo(),
                    chunk.bbox(),
                    chunk.charStart(),
                    chunk.charEnd(),
                    chunk.quote());
        }
    }

    /**
     * Returns the item metadata registered by {@link #replaceChunks}.
     *
     * @return a snapshot of registered items
     */
    public List<KnowledgeItem> items() {
        return List.copyOf(new LinkedHashMap<>(items).values());
    }
}
