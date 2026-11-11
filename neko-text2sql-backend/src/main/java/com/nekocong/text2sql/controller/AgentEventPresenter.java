package com.nekocong.text2sql.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

/**
 * Maps AgentScope events onto the SSE event vocabulary the frontend already understands.
 *
 * <p>Tool steps become {@code step} (with the tool name and, for failures, a readable reason), the
 * pipeline's enforced SQL becomes {@code sql} (carrying the datasource, row count and the "scope
 * applied" flag), and failures become {@code error}. Token deltas stay {@code delta}. Anything else
 * is dropped, so an answer that never queried the database produces no empty SQL block.
 */
@Component
public class AgentEventPresenter {

    private final ObjectMapper objectMapper;

    /**
     * Creates the presenter.
     *
     * @param objectMapper the shared Jackson mapper
     */
    public AgentEventPresenter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Converts one agent event, when it is something the UI should show.
     *
     * @param event the AgentScope event
     * @return the SSE event to emit, or empty for events that carry no user-facing information
     */
    public Optional<ServerSentEvent<String>> present(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta) {
            String text = delta.getDelta();
            if (text == null) {
                return Optional.empty();
            }
            return Optional.of(ServerSentEvent.<String>builder(text).event("delta").build());
        }
        if (event instanceof ToolCallStartEvent start) {
            return sseJson(
                    "step",
                    Map.of(
                            "phase", "tool",
                            "status", "start",
                            "tool", nullSafe(start.getToolCallName())));
        }
        if (event instanceof ToolResultEndEvent end) {
            String state =
                    end.getState() == null ? "unknown" : end.getState().name().toLowerCase(Locale.ROOT);
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("phase", "tool");
            payload.put("status", "end");
            payload.put("state", state);
            payload.put("tool", nullSafe(end.getToolCallName()));
            String reason = failureReason(state);
            if (reason != null) {
                payload.put("reason", reason);
            }
            return sseJson("step", payload);
        }
        if (event instanceof CustomEvent custom) {
            if ("sql".equals(custom.getName())) {
                return sseJson("sql", custom.getValue());
            }
            if ("error".equals(custom.getName())) {
                return sseJson("error", custom.getValue());
            }
        }
        return Optional.empty();
    }

    private Optional<ServerSentEvent<String>> sseJson(String event, Object value) {
        String body = json(value);
        if (body == null) {
            // Never emit a malformed or empty payload: an empty sql block would mislead the user.
            return Optional.empty();
        }
        return Optional.of(ServerSentEvent.<String>builder(body).event(event).build());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String failureReason(String state) {
        return switch (state) {
            case "error" -> "工具执行失败，请查看错误详情或重试";
            case "denied" -> "工具调用被拒绝";
            case "interrupted" -> "工具调用被中断";
            default -> null;
        };
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
