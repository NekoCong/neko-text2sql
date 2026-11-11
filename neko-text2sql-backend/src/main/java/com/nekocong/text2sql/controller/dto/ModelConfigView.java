package com.nekocong.text2sql.controller.dto;

import com.nekocong.text2sql.modelconfig.ModelConfig;

/**
 * A model configuration as returned to clients — the API key is never included.
 *
 * @param id the id
 * @param name the display name
 * @param provider the provider id
 * @param model the model name
 * @param baseUrl the base URL
 * @param isDefault whether it is the default
 * @param hasApiKey whether a key is stored
 */
public record ModelConfigView(
        String id,
        String name,
        String provider,
        String model,
        String baseUrl,
        boolean isDefault,
        boolean hasApiKey) {

    /**
     * Maps a stored config to its view.
     *
     * @param config the stored config
     * @return the view
     */
    public static ModelConfigView of(ModelConfig config) {
        return new ModelConfigView(
                config.id(),
                config.name(),
                config.provider(),
                config.model(),
                config.baseUrl(),
                config.defaultConfig(),
                config.apiKey() != null && !config.apiKey().isBlank());
    }
}
