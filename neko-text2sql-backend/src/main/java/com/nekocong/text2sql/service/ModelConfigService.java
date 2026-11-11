package com.nekocong.text2sql.service;

import com.nekocong.text2sql.modelconfig.ModelConfig;
import com.nekocong.text2sql.modelconfig.ModelFactory;
import com.nekocong.text2sql.repository.ModelConfigRepository;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service layer for per-user model configuration (CRUD + connectivity test), reactive.
 *
 * <p>Owns validation, default-selection and ownership rules; persistence is delegated to
 * {@link ModelConfigRepository} and model construction to {@link ModelFactory}.
 */
@Service
public class ModelConfigService {

    private static final Set<String> SUPPORTED_PROVIDERS =
            Set.of("dashscope", "openai", "deepseek", "compatible");

    private final ModelConfigRepository repository;
    private final ModelFactory factory;

    /**
     * Creates the service.
     *
     * @param repository the model-config repository
     * @param factory the model factory
     */
    public ModelConfigService(ModelConfigRepository repository, ModelFactory factory) {
        this.repository = repository;
        this.factory = factory;
    }

    /**
     * Lists a user's model configs.
     *
     * @param userId the user id
     * @return the configs
     */
    public Flux<ModelConfig> list(String userId) {
        return repository.listByUser(userId);
    }

    /**
     * Creates a model config (the first one becomes the default).
     *
     * @param userId the user id
     * @param command the values
     * @return the created config
     */
    public Mono<ModelConfig> create(String userId, ModelConfigCommand command) {
        return Mono.defer(
                () -> {
                    validate(command);
                    return repository
                            .countByUser(userId)
                            .flatMap(
                                    count -> {
                                        boolean makeDefault =
                                                Boolean.TRUE.equals(command.isDefault()) || count == 0;
                                        Mono<Void> clear =
                                                makeDefault ? repository.clearDefaults(userId) : Mono.empty();
                                        return clear.then(repository.insert(toEntity(null, userId, command, command.apiKey(), makeDefault)));
                                    })
                            .flatMap(inserted -> requireOwned(inserted.id(), userId));
                });
    }

    /**
     * Updates a model config. A blank API key keeps the stored one.
     *
     * @param userId the user id
     * @param id the config id
     * @param command the new values
     * @return the updated config
     */
    public Mono<ModelConfig> update(String userId, String id, ModelConfigCommand command) {
        return Mono.defer(
                () -> {
                    validate(command);
                    return requireOwned(id, userId)
                            .flatMap(
                                    existing -> {
                                        String apiKey =
                                                StringUtils.hasText(command.apiKey()) ? command.apiKey() : existing.apiKey();
                                        boolean makeDefault = Boolean.TRUE.equals(command.isDefault());
                                        Mono<Void> clear =
                                                makeDefault ? repository.clearDefaults(userId) : Mono.empty();
                                        ModelConfig updated = toEntity(id, userId, command, apiKey, makeDefault);
                                        return clear.then(repository.update(updated)).thenReturn(updated);
                                    });
                });
    }

    /**
     * Deletes a model config.
     *
     * @param userId the user id
     * @param id the config id
     * @return a completion signal
     */
    public Mono<Void> delete(String userId, String id) {
        return requireOwned(id, userId).flatMap(existing -> repository.delete(id));
    }

    /**
     * Tests connectivity for a config.
     *
     * @param userId the user id
     * @param id the config id
     * @return the test result
     */
    public Mono<ModelTestResult> test(String userId, String id) {
        return requireOwned(id, userId).flatMap(this::probe);
    }

    /**
     * Loads a config, enforcing ownership.
     *
     * @param id the config id
     * @param userId the requesting user
     * @return the config
     */
    public Mono<ModelConfig> requireOwned(String id, String userId) {
        return repository
                .findById(id)
                .filter(config -> config.userId().equals(userId))
                .switchIfEmpty(
                        Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在")));
    }

    private Mono<ModelTestResult> probe(ModelConfig config) {
        long start = System.currentTimeMillis();
        return factory
                .build(config)
                .stream(List.of(new UserMessage("ping")), List.of(), GenerateOptions.builder().maxTokens(8).build())
                .collectList()
                .map(
                        responses ->
                                new ModelTestResult(
                                        !responses.isEmpty(),
                                        responses.isEmpty() ? "无响应" : "连接成功",
                                        System.currentTimeMillis() - start))
                .onErrorResume(
                        ex ->
                                Mono.just(
                                        new ModelTestResult(
                                                false,
                                                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                                                System.currentTimeMillis() - start)));
    }

    private static ModelConfig toEntity(
            String id, String userId, ModelConfigCommand command, String apiKey, boolean makeDefault) {
        String name = StringUtils.hasText(command.name()) ? command.name().trim() : command.model().trim();
        return new ModelConfig(
                id, userId, name, command.provider(), command.model().trim(), apiKey, command.baseUrl(), makeDefault);
    }

    private static void validate(ModelConfigCommand command) {
        if (!StringUtils.hasText(command.model())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型名称不能为空");
        }
        if (StringUtils.hasText(command.provider())) {
            String provider = command.provider().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_PROVIDERS.contains(provider) && !provider.startsWith("openai")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "provider 仅支持 dashscope / openai (兼容)");
            }
        }
    }
}
