package com.nekocong.text2sql.service;

import com.nekocong.text2sql.nl2sql.ExecutionListener;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Side channel that carries pipeline execution facts (the enforced SQL, failures) alongside the
 * agent's own event stream.
 *
 * <p>The listener emits {@link CustomEvent}s into a per-request serialized unicast sink; {@link
 * #merge(Flux)} merges them with the agent flux and completes the sink when the agent terminates, so
 * the merged stream terminates and no event is lost after completion.
 */
final class AgentSideChannel {

    private static final Logger log = LoggerFactory.getLogger(AgentSideChannel.class);

    private final Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
    private final boolean scopeApplied;

    /**
     * Creates the channel.
     *
     * @param scopeApplied whether the reported SQL was filtered for the caller's scope
     */
    AgentSideChannel(boolean scopeApplied) {
        this.scopeApplied = scopeApplied;
    }

    /**
     * The listener tools can call; it is placed in the AgentScope runtime context.
     *
     * @return the execution listener
     */
    ExecutionListener listener() {
        return new ExecutionListener() {
            @Override
            public void onSuccess(String datasourceId, String sql, int rowCount) {
                emit(
                        new CustomEvent(
                                "sql",
                                Map.of(
                                        "datasourceId", datasourceId == null ? "" : datasourceId,
                                        "sql", sql,
                                        "rowCount", rowCount,
                                        "scopeApplied", scopeApplied)));
            }

            @Override
            public void onFailure(String datasourceId, String message) {
                emit(
                        new CustomEvent(
                                "error",
                                Map.of(
                                        "datasourceId", datasourceId == null ? "" : datasourceId,
                                        "message", message == null ? "" : message)));
            }
        };
    }

    /**
     * Merges the side-channel events into the agent stream.
     *
     * @param agent the agent event stream
     * @return the merged stream, terminating when the agent terminates
     */
    Flux<AgentEvent> merge(Flux<AgentEvent> agent) {
        return Flux.merge(agent.doFinally(signal -> sink.tryEmitComplete()), sink.asFlux());
    }

    private void emit(AgentEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            // The request already terminated (e.g. the client disconnected); dropping is safe here.
            log.debug("Dropped side-channel event {}: {}", event.getClass().getSimpleName(), result);
        }
    }
}
