package com.nekocong.text2sql.modelconfig;

/**
 * A user's model configuration.
 *
 * @param id the id (UUID)
 * @param userId the owning user id
 * @param name a display name
 * @param provider the provider id ({@code dashscope} or {@code openai})
 * @param model the model name
 * @param apiKey the provider API key (server-side only; never returned to clients)
 * @param baseUrl an optional base URL (OpenAI-compatible endpoints)
 * @param defaultConfig whether this is the user's default model
 */
public record ModelConfig(
        String id,
        String userId,
        String name,
        String provider,
        String model,
        String apiKey,
        String baseUrl,
        boolean defaultConfig) {}
