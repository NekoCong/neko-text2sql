package com.nekocong.text2sql.controller.dto;

/**
 * Create/update request for a model configuration.
 *
 * @param name a display name
 * @param provider the provider id ({@code dashscope} or {@code openai})
 * @param model the model name
 * @param apiKey the API key (blank on update keeps the stored key)
 * @param baseUrl an optional base URL
 * @param isDefault whether this should become the user's default model
 */
public record ModelConfigRequest(
        String name,
        String provider,
        String model,
        String apiKey,
        String baseUrl,
        Boolean isDefault) {}
