package com.nekocong.text2sql.repository;

import com.nekocong.text2sql.user.AppUser;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Data-access layer for {@code app_user}, on Spring Data R2DBC's {@link DatabaseClient} (non-blocking).
 */
@Repository
public class UserRepository {

    private static final String COLUMNS =
            "id, username, password_hash, display_name, role, enabled, token_version";

    private final DatabaseClient client;

    /**
     * Creates the repository.
     *
     * @param client the R2DBC database client
     */
    public UserRepository(DatabaseClient client) {
        this.client = client;
    }

    /**
     * Finds a user by username.
     *
     * @param username the username
     * @return the user, if present
     */
    public Mono<AppUser> findByUsername(String username) {
        return client.sql("SELECT " + COLUMNS + " FROM app_user WHERE username = :username")
                .bind("username", username)
                .map((row, meta) -> UserRepository.map(row))
                .one();
    }

    /**
     * Finds a user by id.
     *
     * @param id the user id
     * @return the user, if present
     */
    public Mono<AppUser> findById(String id) {
        return client.sql("SELECT " + COLUMNS + " FROM app_user WHERE id = :id")
                .bind("id", id)
                .map((row, meta) -> UserRepository.map(row))
                .one();
    }

    /**
     * Inserts a user (id is generated app-side as a UUID).
     *
     * @param username the username
     * @param passwordHash the BCrypt hash
     * @param displayName the display name
     * @param role the role name ({@code admin}, {@code student} or {@code counselor})
     * @return the inserted user
     */
    public Mono<AppUser> insert(String username, String passwordHash, String displayName, String role) {
        String id = UUID.randomUUID().toString();
        GenericExecuteSpec spec =
                client.sql(
                                "INSERT INTO app_user (id, username, password_hash, display_name, role, created_at) "
                                        + "VALUES (:id, :username, :passwordHash, :displayName, :role, :createdAt)")
                        .bind("id", id)
                        .bind("username", username)
                        .bind("passwordHash", passwordHash)
                        .bind("role", role)
                        .bind("createdAt", System.currentTimeMillis());
        spec = displayName == null ? spec.bindNull("displayName", String.class) : spec.bind("displayName", displayName);
        // enabled and token_version are left to the column defaults (TRUE / 0).
        return spec.then().thenReturn(new AppUser(id, username, passwordHash, displayName, role, true, 0));
    }

    private static AppUser map(io.r2dbc.spi.Row row) {
        Integer tokenVersion = row.get("token_version", Integer.class);
        return new AppUser(
                row.get("id", String.class),
                row.get("username", String.class),
                row.get("password_hash", String.class),
                row.get("display_name", String.class),
                row.get("role", String.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                tokenVersion == null ? 0 : tokenVersion);
    }
}
