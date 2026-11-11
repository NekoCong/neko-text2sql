package com.nekocong.text2sql.controller.dto;

/**
 * Response body for the synchronous text-to-SQL endpoint.
 *
 * @param sessionId the session identifier the answer belongs to
 * @param content the full assistant answer
 */
public record ChatResponse(String sessionId, String content) {}
