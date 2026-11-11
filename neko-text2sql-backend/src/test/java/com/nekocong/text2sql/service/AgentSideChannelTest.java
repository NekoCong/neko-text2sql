package com.nekocong.text2sql.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.nl2sql.ExecutionListener;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * The side channel is what makes the agent's executed SQL visible. These tests drive the merge with
 * a fake agent event flux and a fake listener, covering success, failure and no-query paths, and
 * assert the merged stream terminates (no hang) without any model.
 */
class AgentSideChannelTest {

    @Test
    void successEmitsTheEnforcedSqlAlongsideToolSteps() {
        AgentSideChannel side = new AgentSideChannel(true);
        ExecutionListener listener = side.listener();

        Flux<AgentEvent> agent =
                Flux.concat(
                        Flux.just(new ToolCallStartEvent("r1", "c1", "query_database")),
                        Flux.defer(
                                () -> {
                                    listener.onSuccess("campus", "SELECT 1", 3);
                                    return Flux.just(
                                            new ToolResultEndEvent(
                                                    "r1", "c1", "query_database", ToolResultState.SUCCESS));
                                }));

        List<AgentEvent> events = side.merge(agent).collectList().block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOf(ToolCallStartEvent.class));
        CustomEvent sql = custom(events, "sql").orElseThrow();
        assertThat(sql.getValue())
                .containsEntry("datasourceId", "campus")
                .containsEntry("sql", "SELECT 1")
                .containsEntry("rowCount", 3)
                .containsEntry("scopeApplied", true);
    }

    @Test
    void failureEmitsAnErrorEventAndStillTerminates() {
        AgentSideChannel side = new AgentSideChannel(true);
        ExecutionListener listener = side.listener();

        Flux<AgentEvent> agent =
                Flux.concat(
                        Flux.just(new ToolCallStartEvent("r1", "c1", "query_database")),
                        Flux.defer(
                                () -> {
                                    listener.onFailure("campus", "表 salary 不在当前数据范围内");
                                    return Flux.just(
                                            new ToolResultEndEvent(
                                                    "r1", "c1", "query_database", ToolResultState.ERROR));
                                }));

        List<AgentEvent> events = side.merge(agent).collectList().block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(custom(events, "sql")).isEmpty();
        assertThat(custom(events, "error").orElseThrow().getValue())
                .containsEntry("message", "表 salary 不在当前数据范围内");
    }

    @Test
    void adminSideChannelMarksTheSqlAsUnfiltered() {
        AgentSideChannel side = new AgentSideChannel(false);
        ExecutionListener listener = side.listener();

        Flux<AgentEvent> agent =
                Flux.defer(
                        () -> {
                            listener.onSuccess("campus", "SELECT 1", 1);
                            return Flux.empty();
                        });

        List<AgentEvent> events = side.merge(agent).collectList().block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(custom(events, "sql").orElseThrow().getValue())
                .containsEntry("scopeApplied", false);
    }

    @Test
    void noQueryProducesNoSqlEvent() {
        AgentSideChannel side = new AgentSideChannel(true);

        Flux<AgentEvent> agent = Flux.just(new TextBlockDeltaEvent("r1", "b1", "你好"));

        List<AgentEvent> events = side.merge(agent).collectList().block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(custom(events, "sql")).isEmpty();
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOf(TextBlockDeltaEvent.class));
    }

    private static Optional<CustomEvent> custom(List<AgentEvent> events, String name) {
        return events.stream()
                .filter(CustomEvent.class::isInstance)
                .map(CustomEvent.class::cast)
                .filter(event -> name.equals(event.getName()))
                .findFirst();
    }
}
