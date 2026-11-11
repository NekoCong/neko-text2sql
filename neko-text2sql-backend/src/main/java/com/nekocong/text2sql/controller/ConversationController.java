package com.nekocong.text2sql.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.conversation.ConversationMessage;
import com.nekocong.text2sql.controller.dto.ConversationMessageView;
import com.nekocong.text2sql.controller.dto.ConversationRequest;
import com.nekocong.text2sql.controller.dto.ConversationView;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.service.ConversationService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Controller layer for conversation management (the left-hand history tree). Delegates to
 * {@link ConversationService}.
 *
 * <p>Reads and writes are scoped to the caller: an admin sees every conversation, a regular user
 * only its own.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversations;
    private final ObjectMapper objectMapper;

    /**
     * Creates the controller.
     *
     * @param conversations the conversation service
     * @param objectMapper the shared Jackson mapper
     */
    public ConversationController(ConversationService conversations, ObjectMapper objectMapper) {
        this.conversations = conversations;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists the conversations visible to the caller.
     *
     * @param jwt the bearer token
     * @return the conversations
     */
    @GetMapping
    public Mono<List<ConversationView>> list(@AuthenticationPrincipal Jwt jwt) {
        return conversations
                .list(jwt.getSubject(), Roles.isAdmin(jwt))
                .map(ConversationView::of)
                .collectList();
    }

    /**
     * Creates a conversation.
     *
     * @param jwt the bearer token
     * @param request the conversation
     * @return the created conversation
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ConversationView> create(
            @AuthenticationPrincipal Jwt jwt, @RequestBody ConversationRequest request) {
        return conversations
                .create(
                        jwt.getSubject(),
                        request.title(),
                        request.datasourceId(),
                        request.modelConfigId())
                .map(ConversationView::of);
    }

    /**
     * Renames a conversation.
     *
     * @param jwt the bearer token
     * @param id the conversation id
     * @param request the new title
     * @return the updated conversation
     */
    @PutMapping("/{id}")
    public Mono<ConversationView> rename(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody ConversationRequest request) {
        String userId = jwt.getSubject();
        boolean isAdmin = Roles.isAdmin(jwt);
        return conversations
                .rename(id, userId, isAdmin, request.title())
                .then(conversations.requireAccessible(id, userId, isAdmin))
                .map(ConversationView::of);
    }

    /**
     * Deletes a conversation and its messages.
     *
     * @param jwt the bearer token
     * @param id the conversation id
     * @return an empty mono
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return conversations.delete(id, jwt.getSubject(), Roles.isAdmin(jwt));
    }

    /**
     * Lists a conversation's messages.
     *
     * @param jwt the bearer token
     * @param id the conversation id
     * @return the messages
     */
    @GetMapping("/{id}/messages")
    public Mono<List<ConversationMessageView>> messages(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return conversations
                .messages(id, jwt.getSubject(), Roles.isAdmin(jwt))
                .map(this::toView)
                .collectList();
    }

    private ConversationMessageView toView(ConversationMessage message) {
        return new ConversationMessageView(
                message.id(),
                message.role(),
                message.content(),
                message.sql(),
                parse(message.chartJson()),
                parse(message.rowsJson()),
                parse(message.citationsJson()),
                message.createdAt());
    }

    private Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            return null;
        }
    }
}
