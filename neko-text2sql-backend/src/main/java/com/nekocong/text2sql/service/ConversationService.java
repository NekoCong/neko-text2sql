package com.nekocong.text2sql.service;

import com.nekocong.text2sql.conversation.Conversation;
import com.nekocong.text2sql.conversation.ConversationMessage;
import com.nekocong.text2sql.repository.ConversationRepository;
import com.nekocong.text2sql.repository.MessageRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service layer for conversations and messages (reactive), with data-scope checks. Persistence is
 * delegated to the conversation/message repositories.
 *
 * <p>Callers pass their role explicitly: an admin may act on every conversation, while a regular
 * user is restricted to the rows it owns.
 */
@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final MessageRepository messages;

    /**
     * Creates the service.
     *
     * @param conversations the conversation repository
     * @param messages the message repository
     */
    public ConversationService(ConversationRepository conversations, MessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    /**
     * Creates a conversation.
     *
     * @param userId the owner id
     * @param title the title (blank → "新会话")
     * @param datasourceId the datasource
     * @param modelConfigId the model config
     * @return the created conversation
     */
    public Mono<Conversation> create(
            String userId, String title, String datasourceId, String modelConfigId) {
        long now = System.currentTimeMillis();
        String resolvedTitle = (title == null || title.isBlank()) ? "新会话" : title.strip();
        Conversation conversation =
                new Conversation(
                        UUID.randomUUID().toString(), userId, resolvedTitle, datasourceId, modelConfigId, now, now);
        return conversations.insert(conversation).thenReturn(conversation);
    }

    /**
     * Loads a conversation the caller is allowed to act on.
     *
     * <p>Admins may access any conversation; everyone else is limited to their own. A conversation
     * the caller may not access is reported as {@code 404} so its existence is not leaked.
     *
     * @param id the conversation id
     * @param userId the requesting user id
     * @param isAdmin whether the caller holds the admin role
     * @return the conversation
     */
    public Mono<Conversation> requireAccessible(String id, String userId, boolean isAdmin) {
        return conversations
                .findById(id)
                .filter(conversation -> isAdmin || conversation.userId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在")));
    }

    /**
     * Appends a user message.
     *
     * @param conversationId the conversation id
     * @param content the message text
     * @return a completion signal
     */
    public Mono<Void> appendUserMessage(String conversationId, String content) {
        return messages.insert(
                new ConversationMessage(
                        UUID.randomUUID().toString(),
                        conversationId,
                        "user",
                        content,
                        null,
                        null,
                        null,
                        null,
                        System.currentTimeMillis()));
    }

    /**
     * Appends an assistant message.
     *
     * @param conversationId the conversation id
     * @param content the text content
     * @param sql the generated SQL
     * @param chartJson the chart spec JSON
     * @param rowsJson the result rows JSON
     * @return a completion signal
     */
    public Mono<Void> appendAssistantMessage(
            String conversationId, String content, String sql, String chartJson, String rowsJson) {
        return appendAssistantMessage(conversationId, content, sql, chartJson, rowsJson, null);
    }

    /**
     * Appends an assistant message carrying original-source citations.
     *
     * @param conversationId the conversation id
     * @param content the text content
     * @param sql the generated SQL
     * @param chartJson the chart spec JSON
     * @param rowsJson the result rows JSON
     * @param citationsJson the citations as JSON, or {@code null}
     * @return a completion signal
     */
    public Mono<Void> appendAssistantMessage(
            String conversationId,
            String content,
            String sql,
            String chartJson,
            String rowsJson,
            String citationsJson) {
        return messages.insert(
                new ConversationMessage(
                        UUID.randomUUID().toString(),
                        conversationId,
                        "assistant",
                        content,
                        sql,
                        chartJson,
                        rowsJson,
                        citationsJson,
                        System.currentTimeMillis()));
    }

    /**
     * Bumps a conversation's {@code updated_at}.
     *
     * @param conversationId the conversation id
     * @return a completion signal
     */
    public Mono<Void> touchConversation(String conversationId) {
        return conversations.touch(conversationId);
    }

    /**
     * Renames a conversation the caller may access.
     *
     * @param id the conversation id
     * @param userId the requesting user id
     * @param isAdmin whether the caller holds the admin role
     * @param title the new title
     * @return a completion signal
     */
    public Mono<Void> rename(String id, String userId, boolean isAdmin, String title) {
        return requireAccessible(id, userId, isAdmin).flatMap(conversation -> conversations.rename(id, title));
    }

    /**
     * Deletes a conversation and its messages, provided the caller may access it.
     *
     * @param id the conversation id
     * @param userId the requesting user id
     * @param isAdmin whether the caller holds the admin role
     * @return a completion signal
     */
    public Mono<Void> delete(String id, String userId, boolean isAdmin) {
        return requireAccessible(id, userId, isAdmin)
                .flatMap(conversation -> messages.deleteByConversation(id).then(conversations.delete(id)));
    }

    /**
     * Lists the conversations the caller may see: every conversation for an admin, only the
     * caller's own otherwise.
     *
     * @param userId the user id
     * @param isAdmin whether the caller holds the admin role
     * @return the conversations
     */
    public Flux<Conversation> list(String userId, boolean isAdmin) {
        return isAdmin ? conversations.listAll() : conversations.listByUser(userId);
    }

    /**
     * Lists a conversation's messages, provided the caller may access it.
     *
     * @param id the conversation id
     * @param userId the requesting user id
     * @param isAdmin whether the caller holds the admin role
     * @return the messages
     */
    public Flux<ConversationMessage> messages(String id, String userId, boolean isAdmin) {
        return requireAccessible(id, userId, isAdmin)
                .flatMapMany(conversation -> messages.listByConversation(id));
    }
}
