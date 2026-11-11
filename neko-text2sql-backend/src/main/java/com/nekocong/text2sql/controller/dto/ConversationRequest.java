package com.nekocong.text2sql.controller.dto;

/**
 * Create/rename request for a conversation.
 *
 * @param title the title
 * @param datasourceId the datasource it targets
 * @param modelConfigId the model config it uses
 */
public record ConversationRequest(String title, String datasourceId, String modelConfigId) {}
