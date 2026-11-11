package com.nekocong.text2sql.modelconfig;

import com.nekocong.text2sql.config.AgentProperties;
import com.nekocong.text2sql.repository.ModelConfigRepository;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Resolves the model to use for a request: the user's default {@link ModelConfig} when present,
 * otherwise the globally configured model ({@code text2sql.agent.model}).
 */
@Component
public class ModelResolver {

    private final ModelConfigRepository repository;
    private final ModelFactory factory;
    private final AgentProperties agentProperties;

    /**
     * Creates the resolver.
     *
     * @param repository the model-config repository
     * @param factory the model factory
     * @param agentProperties the agent/model configuration
     */
    public ModelResolver(
            ModelConfigRepository repository, ModelFactory factory, AgentProperties agentProperties) {
        this.repository = repository;
        this.factory = factory;
        this.agentProperties = agentProperties;
    }

    /**
     * Resolves the model for a user.
     *
     * @param userId the user id (may be {@code null} → global model)
     * @return a mono emitting the model
     */
    public Mono<Model> resolve(String userId) {
        if (userId == null) {
            return globalModel();
        }
        return repository
                .findDefault(userId)
                .map(factory::build)
                .switchIfEmpty(Mono.defer(this::globalModel));
    }

    private Mono<Model> globalModel() {
        return Mono.fromCallable(() -> ModelRegistry.resolve(agentProperties.getModel()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
