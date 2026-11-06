package com.nekocong.text2sql.repository;

import com.nekocong.text2sql.conversation.ConversationMessage;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Data-access layer for {@code message}, on Spring Data R2DBC's {@link DatabaseClient}.
 */
@Repository
public class MessageRepository {

    private final DatabaseClient client;

    /**
     * Creates the repository.
     *
     * @param client the R2DBC database client
     */
    public MessageRepository(DatabaseClient client) {
        this.client = client;
    }

    /**
     * Lists a conversation's messages, oldest first.
     *
     * @param conversationId the conversation id
     * @return the messages
     */
    public Flux<ConversationMessage> listByConversation(String conversationId) {
        return client.sql(
                        "SELECT id, conversation_id, role, content, sql_text, chart_json, rows_json, citations_json, created_at "
                                + "FROM message WHERE conversation_id = :conversationId ORDER BY created_at ASC, id ASC")
                .bind("conversationId", conversationId)
                .map((row, meta) -> map(row))
                .all();
    }

    /**
     * Inserts a message.
     *
     * @param message the message
     * @return a completion signal
     */
    public Mono<Void> insert(ConversationMessage message) {
        GenericExecuteSpec spec =
                client.sql(
                                "INSERT INTO message (id, conversation_id, role, content, sql_text, chart_json, rows_json, citations_json, created_at) "
                                        + "VALUES (:id, :conversationId, :role, :content, :sqlText, :chartJson, :rowsJson, :citationsJson, :createdAt)")
                        .bind("id", message.id())
                        .bind("conversationId", message.conversationId())
                        .bind("role", message.role())
                        .bind("createdAt", message.createdAt());
        spec = bindNullable(spec, "content", message.content());
        spec = bindNullable(spec, "sqlText", message.sql());
        spec = bindNullable(spec, "chartJson", message.chartJson());
        spec = bindNullable(spec, "rowsJson", message.rowsJson());
        spec = bindNullable(spec, "citationsJson", message.citationsJson());
        return spec.then();
    }

    /**
     * Deletes all messages of a conversation.
     *
     * @param conversationId the conversation id
     * @return a completion signal
     */
    public Mono<Void> deleteByConversation(String conversationId) {
        return client.sql("DELETE FROM message WHERE conversation_id = :conversationId")
                .bind("conversationId", conversationId)
                .then();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static ConversationMessage map(io.r2dbc.spi.Row row) {
        return new ConversationMessage(
                row.get("id", String.class),
                row.get("conversation_id", String.class),
                row.get("role", String.class),
                row.get("content", String.class),
                row.get("sql_text", String.class),
                row.get("chart_json", String.class),
                row.get("rows_json", String.class),
                row.get("citations_json", String.class),
                row.get("created_at", Long.class));
    }
}
