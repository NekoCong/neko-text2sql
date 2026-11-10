package com.nekocong.text2sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.security.AccessFixtures;
import com.nekocong.text2sql.security.DataScope;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * The scope clarification is appended to the answer stream, never buffered: deltas still flow one by
 * one, the clarification is emitted at most once, and {@code done} stays the last event.
 *
 * <p>Only the presenter and the guard are involved, so no agent or model is needed.
 */
class Text2SqlControllerAgentStreamTest {

    private final Text2SqlController controller =
            new Text2SqlController(null, null, null, null, new AgentEventPresenter(new ObjectMapper()), new ObjectMapper());

    @Test
    void theClarificationIsAppendedOnceAndDoneStaysLast() {
        List<ServerSentEvent<String>> events =
                stream(
                        AccessFixtures.student(),
                        delta("1 班的平均分是 87.5，"),
                        delta("2 班不存在相关成绩。"));

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("delta", "delta", "delta", "done");
        assertThat(events.get(0).data()).isEqualTo("1 班的平均分是 87.5，");
        assertThat(events.get(1).data()).isEqualTo("2 班不存在相关成绩。");
        assertThat(events.get(2).data())
                .startsWith("（说明：当前账号的数据范围仅限学生（仅本人：学号 2023001，班级 1）");
        assertThat(events.get(3).event()).isEqualTo("done");
        assertThat(events).filteredOn(e -> "delta".equals(e.event()) && e.data() != null
                        && e.data().startsWith("（说明："))
                .hasSize(1);
    }

    @Test
    void anAnswerThatAlreadyExplainsTheRangeGetsNoExtraDelta() {
        List<ServerSentEvent<String>> events =
                stream(
                        AccessFixtures.student(),
                        delta("2 班不在当前账号的数据范围内，因此不存在可见记录。"));

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("delta", "done");
        assertThat(events.get(1).event()).isEqualTo("done");
    }

    @Test
    void anAdminNeverGetsTheClarification() {
        List<ServerSentEvent<String>> events =
                stream(AccessFixtures.admin(), delta("2 班不存在相关成绩。"));

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("delta", "done");
    }

    @Test
    void nonDeltaEventsDoNotBreakTheOrdering() {
        List<ServerSentEvent<String>> events =
                stream(
                        AccessFixtures.student(),
                        new ToolCallStartEvent("r1", "c1", "query_database"),
                        delta("没有数据。"));

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("step", "delta", "delta", "done");
        assertThat(events).filteredOn(e -> "done".equals(e.event())).hasSize(1);
    }

    private List<ServerSentEvent<String>> stream(DataScope scope, AgentEvent... events) {
        return controller.agentStream(scope, Flux.just(events)).collectList().block();
    }

    private static TextBlockDeltaEvent delta(String text) {
        return new TextBlockDeltaEvent("r1", "b1", text);
    }
}
