package com.nekocong.text2sql.conversation;

/**
 * A persisted chat message.
 *
 * @param id the message id (UUID)
 * @param conversationId the owning conversation
 * @param role {@code user} or {@code assistant}
 * @param content the text content
 * @param sql the generated SQL (assistant messages)
 * @param chartJson the chart spec as JSON (assistant messages)
 * @param rowsJson the query result as JSON (assistant messages)
 * @param citationsJson the original-source citations as JSON (assistant messages), or {@code null}
 * @param createdAt epoch millis
 */
public record ConversationMessage(
        String id,
        String conversationId,
        String role,
        String content,
        String sql,
        String chartJson,
        String rowsJson,
        String citationsJson,
        long createdAt) {
}
