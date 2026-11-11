package com.nekocong.text2sql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for embedding-based schema retrieval (RAG), bound from {@code text2sql.rag.*}.
 *
 * <p>Disabled by default: for the small demo schema, full-schema grounding is optimal and needs no
 * embedding model. Enable it (and provide an embedding API key) to switch
 * {@code FullSchemaProvider} out for {@code RagSchemaProvider}.
 */
@ConfigurationProperties(prefix = "text2sql.rag")
public class RagProperties {

    /** Whether embedding-based schema retrieval is enabled. */
    private boolean enabled = false;

    /** Embedding provider id (currently {@code dashscope}). */
    private String provider = "dashscope";

    /** Embedding model name. */
    private String embeddingModel = "text-embedding-v3";

    /** Embedding vector dimensions. */
    private int dimensions = 1024;

    /** Number of tables to retrieve per question. */
    private int topK = 4;

    /** Minimum cosine similarity for a table to be considered relevant. */
    private double scoreThreshold = 0.0;

    /** Optional explicit API key; falls back to the provider's environment variable when unset. */
    private String apiKey;

    /** Optional base URL override. */
    private String baseUrl;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getScoreThreshold() {
        return scoreThreshold;
    }

    public void setScoreThreshold(double scoreThreshold) {
        this.scoreThreshold = scoreThreshold;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
