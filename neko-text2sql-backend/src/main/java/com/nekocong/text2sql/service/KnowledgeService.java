package com.nekocong.text2sql.service;
import com.nekocong.text2sql.knowledge.DocumentInfo;
import com.nekocong.text2sql.knowledge.SearchHit;

import com.nekocong.text2sql.config.KnowledgeProperties;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * In-memory knowledge base (a trimmed-down MS-RAG document index).
 *
 * <p>Documents are chunked, embedded and stored per namespace. Retrieval ranks chunks by cosine
 * similarity when an {@link EmbeddingModel} is available (i.e. {@code text2sql.rag.enabled=true}),
 * and falls back to lexical overlap + substring matching otherwise — so the knowledge base is
 * usable, and testable, without an embedding API key.
 */
@Service
public class KnowledgeService {

    private static final String DEFAULT_NAMESPACE = "default";

    private final ObjectProvider<EmbeddingModel> embeddings;
    private final KnowledgeProperties properties;
    private final Map<String, List<Chunk>> chunks = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DocumentInfo>> documents = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /**
     * Creates the service.
     *
     * @param embeddings the optional embedding model (present only when RAG is enabled)
     * @param properties the knowledge-base configuration
     */
    public KnowledgeService(ObjectProvider<EmbeddingModel> embeddings, KnowledgeProperties properties) {
        this.embeddings = embeddings;
        this.properties = properties;
    }

    /**
     * Ingests (or replaces) a document.
     *
     * @param namespace the namespace (defaults to {@code "default"})
     * @param name the document name
     * @param content the document text
     * @return the document info
     */
    public DocumentInfo ingest(String namespace, String name, String content) {
        String ns = normalize(namespace);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("'name' is required");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("'content' is required");
        }
        remove(ns, name);

        List<Chunk> created = new ArrayList<>();
        for (String piece : chunk(content)) {
            created.add(new Chunk(String.valueOf(sequence.incrementAndGet()), name, piece, embed(piece)));
        }
        chunks.computeIfAbsent(ns, key -> new CopyOnWriteArrayList<>()).addAll(created);
        documents
                .computeIfAbsent(ns, key -> new ConcurrentHashMap<>())
                .put(name, new DocumentInfo(ns, name, created.size()));
        return new DocumentInfo(ns, name, created.size());
    }

    /**
     * Removes a document.
     *
     * @param namespace the namespace (defaults to {@code "default"})
     * @param name the document name
     * @return the number of chunks removed
     */
    public int remove(String namespace, String name) {
        String ns = normalize(namespace);
        Map<String, DocumentInfo> docs = documents.get(ns);
        if (docs == null || docs.remove(name) == null) {
            return 0;
        }
        List<Chunk> all = chunks.get(ns);
        if (all == null) {
            return 0;
        }
        int before = all.size();
        all.removeIf(chunk -> chunk.documentName().equals(name));
        return before - all.size();
    }

    /**
     * Lists the documents of a namespace, with their chunk counts recomputed.
     *
     * @param namespace the namespace (defaults to {@code "default"})
     * @return the documents
     */
    public List<DocumentInfo> list(String namespace) {
        String ns = normalize(namespace);
        Map<String, Long> counts =
                chunks.getOrDefault(ns, List.of()).stream()
                        .collect(Collectors.groupingBy(Chunk::documentName, Collectors.counting()));
        Map<String, DocumentInfo> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            result.put(entry.getKey(), new DocumentInfo(ns, entry.getKey(), entry.getValue().intValue()));
        }
        return List.copyOf(result.values());
    }

    /**
     * Searches a namespace.
     *
     * @param namespace the namespace (defaults to {@code "default"})
     * @param query the query text
     * @param topK maximum hits (defaults to the configured value)
     * @return the ranked hits
     */
    public List<SearchHit> search(String namespace, String query, Integer topK) {
        String ns = normalize(namespace);
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = (topK == null || topK <= 0) ? properties.getTopK() : topK;
        List<Chunk> all = chunks.getOrDefault(ns, List.of());
        double[] queryVector = embed(query);
        return all.stream()
                .map(chunk -> new SearchHit(chunk.documentName(), chunk.text(), score(queryVector, query, chunk)))
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(limit)
                .toList();
    }

    private List<String> chunk(String content) {
        int max = Math.max(200, properties.getChunkSize());
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : content.split("\\n\\s*\\n")) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.length() + trimmed.length() > max) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            if (trimmed.length() > max) {
                for (int i = 0; i < trimmed.length(); i += max) {
                    pieces.add(trimmed.substring(i, Math.min(trimmed.length(), i + max)));
                }
            } else {
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(trimmed);
            }
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    private double score(double[] queryVector, String query, Chunk chunk) {
        if (queryVector != null && chunk.vector() != null) {
            return cosine(queryVector, chunk.vector());
        }
        return lexical(query, chunk.text());
    }

    private static double lexical(String query, String text) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        if (lowerText.contains(query.toLowerCase(Locale.ROOT).strip())) {
            return 1.0;
        }
        var queryTokens = tokens(query);
        var textTokens = tokens(text);
        if (queryTokens.isEmpty() || textTokens.isEmpty()) {
            return 0;
        }
        long overlap = queryTokens.stream().filter(textTokens::contains).count();
        return (double) overlap / queryTokens.size();
    }

    private static java.util.Set<String> tokens(String value) {
        return java.util.Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^a-z0-9\\u4e00-\\u9fff]+"))
                .filter(token -> token.length() >= 2)
                .collect(Collectors.toSet());
    }

    private double[] embed(String text) {
        EmbeddingModel model = embeddings.getIfAvailable();
        if (model == null) {
            return null;
        }
        try {
            return model.embed(TextBlock.builder().text(text).build()).block();
        } catch (RuntimeException ex) {
            return null; // fall back to lexical scoring
        }
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

    private static String normalize(String namespace) {
        return (namespace == null || namespace.isBlank()) ? DEFAULT_NAMESPACE : namespace;
    }

    private record Chunk(String id, String documentName, String text, double[] vector) {}
}
