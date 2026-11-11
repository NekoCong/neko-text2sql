package com.nekocong.text2sql.controller.dto;

import com.nekocong.text2sql.conversation.Conversation;

/**
 * A conversation as returned to clients.
 *
 * @param id the id
 * @param title the title
 * @param datasourceId the datasource
 * @param modelConfigId the model config
 * @param createdAt epoch millis
 * @param updatedAt epoch millis
 */
public record ConversationView(
        String id,
        String title,
        String datasourceId,
        String modelConfigId,
        long createdAt,
        long updatedAt) {

    /**
     * Maps a stored conversation to its view.
     *
     * @param conversation the stored conversation
     * @return the view
     */
    public static ConversationView of(Conversation conversation) {
        return new ConversationView(
                conversation.id(),
                conversation.title(),
                conversation.datasourceId(),
                conversation.modelConfigId(),
                conversation.createdAt(),
                conversation.updatedAt());
    }
}
