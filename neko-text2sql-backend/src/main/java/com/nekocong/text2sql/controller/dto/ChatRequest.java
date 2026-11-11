package com.nekocong.text2sql.controller.dto;

/**
 * Request body for the synchronous text-to-SQL endpoint.
 *
 * <p>The caller identity is always taken from the bearer token; there is deliberately no user field
 * in the body, so a client cannot act on behalf of another account.
 *
 * @param message the natural-language question
 * @param sessionId optional session identifier (defaults to {@code "default"})
 * @param datasourceId optional datasource id (defaults to the configured default datasource)
 */
public record ChatRequest(String message, String sessionId, String datasourceId) {

    /**
     * Returns a non-blank session id.
     *
     * @return the session id or {@code "default"}
     */
    public String sessionIdOrDefault() {
        return (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
    }
}
