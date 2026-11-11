package com.nekocong.text2sql.config;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Provides the {@link EmbeddingModel} used by embedding-based schema retrieval.
 *
 * <p>Only active when {@code text2sql.rag.enabled=true}. The bean is {@code @ConditionalOnMissingBean},
 * so tests (or other providers) can supply their own without an API key.
 */
@Configuration
@ConditionalOnProperty(prefix = "text2sql.rag", name = "enabled", havingValue = "true")
public class RagConfig {

    /**
     * Builds the DashScope text-embedding model from configuration.
     *
     * @param properties the RAG configuration
     * @return the embedding model
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel embeddingModel(RagProperties properties) {
        DashScopeTextEmbedding.Builder builder =
                DashScopeTextEmbedding.builder()
                        .modelName(properties.getEmbeddingModel())
                        .dimensions(properties.getDimensions());
        String apiKey =
                StringUtils.hasText(properties.getApiKey())
                        ? properties.getApiKey()
                        : System.getenv("DASHSCOPE_API_KEY");
        if (StringUtils.hasText(apiKey)) {
            builder.apiKey(apiKey);
        }
        if (StringUtils.hasText(properties.getBaseUrl())) {
            builder.baseUrl(properties.getBaseUrl());
        }
        return builder.build();
    }
}
