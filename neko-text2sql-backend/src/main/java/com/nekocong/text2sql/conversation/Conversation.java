package com.nekocong.text2sql.conversation;

/**
 * A chat conversation.
 *
 * @param id the conversation id (UUID)
 * @param userId the owning user id
 * @param title the title
 * @param datasourceId the datasource it targets
 * @param modelConfigId the model config it uses (may be {@code null})
 * @param createdAt epoch millis
 * @param updatedAt epoch millis
 */
public record Conversation(
        String id,
        String userId,
        String title,
        String datasourceId,
        String modelConfigId,
        long createdAt,
        long updatedAt) {}
