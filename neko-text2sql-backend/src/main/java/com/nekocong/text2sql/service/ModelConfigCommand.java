package com.nekocong.text2sql.service;

/**
 * Input for creating/updating a model configuration (service-layer command, decoupled from the web
 * DTO).
 *
 * @param name display name
 * @param provider provider id ({@code dashscope} / {@code openai})
 * @param model model name
 * @param apiKey API key (blank on update keeps the stored key)
 * @param baseUrl optional base URL
 * @param isDefault whether it should become the default
 */
public record ModelConfigCommand(
        String name, String provider, String model, String apiKey, String baseUrl, Boolean isDefault) {}
