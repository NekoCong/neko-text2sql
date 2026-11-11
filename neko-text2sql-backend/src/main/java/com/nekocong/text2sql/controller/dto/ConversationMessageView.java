package com.nekocong.text2sql.controller.dto;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A persisted message as returned to clients. Chart, rows and citations are embedded as JSON objects.
 *
 * <p>The fields are plain {@code Object} (parsed to {@code Map}/{@code List}) on purpose: Spring
 * Boot 4's HTTP codec serialises with Jackson 3, while this application's mapper is Jackson 2 — a
 * Jackson 2 {@code JsonNode} would be treated as an ordinary bean and rendered as its internal
 * flags instead of the document.
 *
 * @param id the message id
 * @param role {@code user} or {@code assistant}
 * @param content the text content
 * @param sql the generated SQL
 * @param chart the chart spec, or {@code null}
 * @param rows the result rows, or {@code null}
 * @param citations the original-source citations, or {@code null}
 * @param createdAt epoch millis
 */
public record ConversationMessageView(
        String id,
        String role,
        String content,
        String sql,
        Object chart,
        Object rows,
        Object citations,
        long createdAt) {}
