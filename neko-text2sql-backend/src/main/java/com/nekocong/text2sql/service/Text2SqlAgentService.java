package com.nekocong.text2sql.service;

import com.nekocong.text2sql.config.HarnessAgentFactory;
import com.nekocong.text2sql.modelconfig.ModelResolver;
import com.nekocong.text2sql.nl2sql.ExecutionListener;
import com.nekocong.text2sql.security.DataScope;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Bridges the web layer to the AgentScope agent runtime.
 *
 * <p>Both methods stream the raw {@link AgentEvent} flux; token level aggregation lives in the
 * controller / frontend. The agent is built with the caller's configured model, and the caller's
 * {@link DataScope} is placed into the runtime context so the tools execute with the same identity
 * as the deterministic pipeline. Agent execution is offloaded to a bounded-elastic scheduler so the
 * Netty event loop is never blocked.
 */
@Service
public class Text2SqlAgentService {

    private final HarnessAgentFactory agentFactory;
    private final ModelResolver modelResolver;

    /**
     * Creates the service.
     *
     * @param agentFactory factory building a fresh, single-use agent per request
     * @param modelResolver resolves the user's model
     */
    public Text2SqlAgentService(HarnessAgentFactory agentFactory, ModelResolver modelResolver) {
        this.agentFactory = agentFactory;
        this.modelResolver = modelResolver;
    }

    /**
     * Streams the agent's events for a single caller question.
     *
     * @param message the natural-language question
     * @param sessionId session identifier used for conversation state
     * @param scope the caller's resolved data scope
     * @return a cold flux of agent events
     */
    public Flux<AgentEvent> stream(String message, String sessionId, DataScope scope) {
        return modelResolver
                .resolve(scope.userId())
                .flatMapMany(
                        model ->
                                Flux.defer(
                                        () -> {
                                            AgentSideChannel side = new AgentSideChannel(!scope.isAdmin());
                                            RuntimeContext ctx =
                                                    RuntimeContext.builder()
                                                            .sessionId(sessionId)
                                                            .userId(scope.userId())
                                                            .build();
                                            // Tools declare DataScope / ExecutionListener parameters;
                                            // AgentScope resolves them from this context instead of
                                            // the model-facing input.
                                            ctx.put(DataScope.class, scope);
                                            ctx.put(ExecutionListener.class, side.listener());
                                            return side.merge(
                                                    agentFactory
                                                            .create(model, scope)
                                                            .streamEvents(new UserMessage(message), ctx));
                                        }))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Collects the full textual answer for a single caller question.
     *
     * @param message the natural-language question
     * @param sessionId session identifier used for conversation state
     * @param scope the caller's resolved data scope
     * @return the concatenated assistant text
     */
    public Mono<String> query(String message, String sessionId, DataScope scope) {
        return stream(message, sessionId, scope)
                .filter(event -> event instanceof TextBlockDeltaEvent)
                .map(event -> ((TextBlockDeltaEvent) event).getDelta())
                .collect(StringBuilder::new, StringBuilder::append)
                .map(StringBuilder::toString);
    }
}
