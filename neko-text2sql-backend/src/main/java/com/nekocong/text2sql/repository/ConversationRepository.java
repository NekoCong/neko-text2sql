package com.nekocong.text2sql.repository;

import com.nekocong.text2sql.conversation.Conversation;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Data-access layer for {@code conversation}, on Spring Data R2DBC's {@link DatabaseClient}.
 */
@Repository
public class ConversationRepository {

    private static final String COLUMNS =
            "id, user_id, title, datasource_id, model_config_id, created_at, updated_at";

    private final DatabaseClient client;

    /**
     * Creates the repository.
     *
     * @param client the R2DBC database client
     */
    public ConversationRepository(DatabaseClient client) {
        this.client = client;
    }

    /**
     * Lists a user's conversations, most recently updated first.
     *
     * @param userId the user id
     * @return the conversations
     */
    public Flux<Conversation> listByUser(String userId) {
        return client.sql(
                        "SELECT " + COLUMNS + " FROM conversation WHERE user_id = :userId ORDER BY updated_at DESC")
                .bind("userId", userId)
                .map((row, meta) -> map(row))
                .all();
    }

    /**
     * Lists every conversation regardless of owner, most recently updated first. Used by the
     * cross-user view available to admins.
     *
     * @return all conversations
     */
    public Flux<Conversation> listAll() {
        return client.sql("SELECT " + COLUMNS + " FROM conversation ORDER BY updated_at DESC")
                .map((row, meta) -> map(row))
                .all();
    }

    /**
     * Finds a conversation by id.
     *
     * @param id the conversation id
     * @return the conversation, if present
     */
    public Mono<Conversation> findById(String id) {
        return client.sql("SELECT " + COLUMNS + " FROM conversation WHERE id = :id")
                .bind("id", id)
                .map((row, meta) -> map(row))
                .one();
    }

    /**
     * Inserts a conversation.
     *
     * @param conversation the conversation
     * @return a completion signal
     */
    public Mono<Void> insert(Conversation conversation) {
        GenericExecuteSpec spec =
                client.sql(
                                "INSERT INTO conversation (id, user_id, title, datasource_id, model_config_id, created_at, updated_at) "
                                        + "VALUES (:id, :userId, :title, :datasourceId, :modelConfigId, :createdAt, :updatedAt)")
                        .bind("id", conversation.id())
                        .bind("userId", conversation.userId())
                        .bind("title", conversation.title())
                        .bind("createdAt", conversation.createdAt())
                        .bind("updatedAt", conversation.updatedAt());
        spec = bindNullable(spec, "datasourceId", conversation.datasourceId());
        spec = bindNullable(spec, "modelConfigId", conversation.modelConfigId());
        return spec.then();
    }

    /**
     * Renames a conversation.
     *
     * @param id the conversation id
     * @param title the new title
     * @return a completion signal
     */
    public Mono<Void> rename(String id, String title) {
        return client.sql("UPDATE conversation SET title = :title, updated_at = :updatedAt WHERE id = :id")
                .bind("title", title)
                .bind("updatedAt", System.currentTimeMillis())
                .bind("id", id)
                .then();
    }

    /**
     * Bumps a conversation's {@code updated_at}.
     *
     * @param id the conversation id
     * @return a completion signal
     */
    public Mono<Void> touch(String id) {
        return client.sql("UPDATE conversation SET updated_at = :updatedAt WHERE id = :id")
                .bind("updatedAt", System.currentTimeMillis())
                .bind("id", id)
                .then();
    }

    /**
     * Deletes a conversation.
     *
     * @param id the conversation id
     * @return a completion signal
     */
    public Mono<Void> delete(String id) {
        return client.sql("DELETE FROM conversation WHERE id = :id").bind("id", id).then();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static Conversation map(io.r2dbc.spi.Row row) {
        return new Conversation(
                row.get("id", String.class),
                row.get("user_id", String.class),
                row.get("title", String.class),
                row.get("datasource_id", String.class),
                row.get("model_config_id", String.class),
                row.get("created_at", Long.class),
                row.get("updated_at", Long.class));
    }
}
