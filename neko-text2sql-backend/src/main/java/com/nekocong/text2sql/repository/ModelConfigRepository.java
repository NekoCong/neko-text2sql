package com.nekocong.text2sql.repository;

import com.nekocong.text2sql.modelconfig.ModelConfig;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Data-access layer for {@code model_config}, on Spring Data R2DBC's {@link DatabaseClient}.
 */
@Repository
public class ModelConfigRepository {

    private final DatabaseClient client;

    /**
     * Creates the repository.
     *
     * @param client the R2DBC database client
     */
    public ModelConfigRepository(DatabaseClient client) {
        this.client = client;
    }

    /**
     * Lists a user's configs (default first, newest first).
     *
     * @param userId the user id
     * @return the configs
     */
    public Flux<ModelConfig> listByUser(String userId) {
        return client.sql(
                        "SELECT id, user_id, name, provider, model, api_key, base_url, is_default "
                                + "FROM model_config WHERE user_id = :userId ORDER BY is_default DESC, id DESC")
                .bind("userId", userId)
                .map((row, meta) -> map(row))
                .all();
    }

    /**
     * Finds a config by id.
     *
     * @param id the config id
     * @return the config, if present
     */
    public Mono<ModelConfig> findById(String id) {
        return client.sql(
                        "SELECT id, user_id, name, provider, model, api_key, base_url, is_default "
                                + "FROM model_config WHERE id = :id")
                .bind("id", id)
                .map((row, meta) -> map(row))
                .one();
    }

    /**
     * Finds a user's default config.
     *
     * @param userId the user id
     * @return the default config, if present
     */
    public Mono<ModelConfig> findDefault(String userId) {
        return client.sql(
                        "SELECT id, user_id, name, provider, model, api_key, base_url, is_default "
                                + "FROM model_config WHERE user_id = :userId AND is_default = TRUE ORDER BY id DESC")
                .bind("userId", userId)
                .map((row, meta) -> map(row))
                .one();
    }

    /**
     * Inserts a config (id generated app-side).
     *
     * @param config the config (id ignored)
     * @return the inserted config
     */
    public Mono<ModelConfig> insert(ModelConfig config) {
        String id = UUID.randomUUID().toString();
        GenericExecuteSpec spec =
                client.sql(
                                "INSERT INTO model_config (id, user_id, name, provider, model, api_key, base_url, is_default, created_at) "
                                        + "VALUES (:id, :userId, :name, :provider, :model, :apiKey, :baseUrl, :isDefault, :createdAt)")
                        .bind("id", id)
                        .bind("userId", config.userId())
                        .bind("name", config.name())
                        .bind("provider", config.provider())
                        .bind("model", config.model())
                        .bind("isDefault", config.defaultConfig())
                        .bind("createdAt", System.currentTimeMillis());
        spec = bindNullable(spec, "apiKey", config.apiKey());
        spec = bindNullable(spec, "baseUrl", config.baseUrl());
        return spec.then()
                .thenReturn(
                        new ModelConfig(
                                id,
                                config.userId(),
                                config.name(),
                                config.provider(),
                                config.model(),
                                config.apiKey(),
                                config.baseUrl(),
                                config.defaultConfig()));
    }

    /**
     * Updates a config.
     *
     * @param config the new values
     * @return a completion signal
     */
    public Mono<Void> update(ModelConfig config) {
        GenericExecuteSpec spec =
                client.sql(
                                "UPDATE model_config SET name = :name, provider = :provider, model = :model, "
                                        + "api_key = :apiKey, base_url = :baseUrl, is_default = :isDefault WHERE id = :id")
                        .bind("name", config.name())
                        .bind("provider", config.provider())
                        .bind("model", config.model())
                        .bind("isDefault", config.defaultConfig())
                        .bind("id", config.id());
        spec = bindNullable(spec, "apiKey", config.apiKey());
        spec = bindNullable(spec, "baseUrl", config.baseUrl());
        return spec.then();
    }

    /**
     * Deletes a config.
     *
     * @param id the config id
     * @return a completion signal
     */
    public Mono<Void> delete(String id) {
        return client.sql("DELETE FROM model_config WHERE id = :id").bind("id", id).then();
    }

    /**
     * Clears the default flag for all of a user's configs.
     *
     * @param userId the user id
     * @return a completion signal
     */
    public Mono<Void> clearDefaults(String userId) {
        return client.sql("UPDATE model_config SET is_default = FALSE WHERE user_id = :userId")
                .bind("userId", userId)
                .then();
    }

    /**
     * Counts a user's configs.
     *
     * @param userId the user id
     * @return the count
     */
    public Mono<Long> countByUser(String userId) {
        return listByUser(userId).count();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static ModelConfig map(io.r2dbc.spi.Row row) {
        return new ModelConfig(
                row.get("id", String.class),
                row.get("user_id", String.class),
                row.get("name", String.class),
                row.get("provider", String.class),
                row.get("model", String.class),
                row.get("api_key", String.class),
                row.get("base_url", String.class),
                Boolean.TRUE.equals(row.get("is_default", Boolean.class)));
    }
}
