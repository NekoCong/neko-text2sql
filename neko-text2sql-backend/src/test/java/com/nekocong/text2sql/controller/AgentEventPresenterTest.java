package com.nekocong.text2sql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

/**
 * The agent stream must surface steps, the enforced SQL and failures; everything else stays out of
 * the SSE stream.
 */
class AgentEventPresenterTest {

    private final AgentEventPresenter presenter =
            new AgentEventPresenter(new ObjectMapper());

    @Test
    void tokenDeltasStayDeltas() {
        ServerSentEvent<String> event =
                present(new TextBlockDeltaEvent("r1", "b1", "你好"));

        assertThat(event.event()).isEqualTo("delta");
        assertThat(event.data()).isEqualTo("你好");
    }

    @Test
    void toolCallsBecomeSteps() throws Exception {
        ServerSentEvent<String> start =
                present(new ToolCallStartEvent("r1", "c1", "query_database"));
        assertThat(start.event()).isEqualTo("step");
        assertThat(new ObjectMapper().readTree(start.data()).get("tool").asText())
                .isEqualTo("query_database");
        assertThat(new ObjectMapper().readTree(start.data()).get("status").asText()).isEqualTo("start");

        ServerSentEvent<String> end =
                present(new ToolResultEndEvent("r1", "c1", "query_database", ToolResultState.ERROR));
        assertThat(end.event()).isEqualTo("step");
        assertThat(new ObjectMapper().readTree(end.data()).get("state").asText()).isEqualTo("error");
        assertThat(new ObjectMapper().readTree(end.data()).get("reason").asText())
                .contains("工具执行失败");
    }

    @Test
    void deniedToolCallsCarryAReadableReason() throws Exception {
        ServerSentEvent<String> denied =
                present(new ToolResultEndEvent("r1", "c1", "query_database", ToolResultState.DENIED));

        assertThat(new ObjectMapper().readTree(denied.data()).get("reason").asText())
                .contains("拒绝");
    }

    @Test
    void sqlEventsCarryTheEnforcedStatement() throws Exception {
        ServerSentEvent<String> event =
                present(
                        new CustomEvent(
                                "sql",
                                Map.of(
                                        "datasourceId", "campus",
                                        "sql", "SELECT 1",
                                        "rowCount", 1,
                                        "scopeApplied", true)));

        assertThat(event.event()).isEqualTo("sql");
        var json = new ObjectMapper().readTree(event.data());
        assertThat(json.get("sql").asText()).isEqualTo("SELECT 1");
        assertThat(json.get("rowCount").asInt()).isEqualTo(1);
        assertThat(json.get("scopeApplied").asBoolean()).isTrue();
    }

    @Test
    void failureEventsBecomeErrors() throws Exception {
        ServerSentEvent<String> event =
                present(new CustomEvent("error", Map.of("message", "表 salary 不在当前数据范围内")));

        assertThat(event.event()).isEqualTo("error");
        assertThat(new ObjectMapper().readTree(event.data()).get("message").asText())
                .contains("salary");
    }

    @Test
    void unrelatedEventsAreDropped() {
        assertThat(presenter.present(new CustomEvent("noop", Map.of("k", "v")))).isEmpty();
    }

    private ServerSentEvent<String> present(io.agentscope.core.event.AgentEvent event) {
        Optional<ServerSentEvent<String>> result = presenter.present(event);
        assertThat(result).isPresent();
        return result.orElseThrow();
    }
}
