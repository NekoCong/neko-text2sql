package com.nekocong.text2sql.repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Data-access layer for the two access-control tables: {@code user_scope} (principal attributes)
 * and {@code datasource_access} (role -> datasource whitelist).
 *
 * <p>These live in the application database on purpose: resolving who the caller is must never
 * require reading the protected business database.
 *
 * <p>Transitional: {@link #scopes} and {@link #datasourcesForRole} read the legacy tables.
 * Principal attributes are now resolved by {@code R2dbcAccessControlRepository} and datasource
 * grants by {@code access_role_datasource}; these two methods remain only until the legacy admin
 * matrix is replaced (task 5.5), and must not gain new callers.
 */
@Repository
public class AccessRepository {

    private final DatabaseClient client;

    /**
     * Creates the repository.
     *
     * @param client the R2DBC database client
     */
    public AccessRepository(DatabaseClient client) {
        this.client = client;
    }

    /** One {@code user_scope} row. */
    public record ScopeRow(String scopeType, String scopeValue) {}

    /**
     * Reads every scope attribute of a user.
     *
     * <p>TODO(task 5.5): legacy {@code user_scope} read; enforcement uses {@code
     * R2dbcAccessControlRepository.principal} instead.
     *
     * @param userId the user id
     * @return the scope rows, in insertion order
     */
    public Mono<List<ScopeRow>> scopes(String userId) {
        return client.sql("SELECT scope_type, scope_value FROM user_scope WHERE user_id = :userId")
                .bind("userId", userId)
                .map(
                        (row, meta) ->
                                new ScopeRow(
                                        row.get("scope_type", String.class),
                                        row.get("scope_value", String.class)))
                .all()
                .collectList();
    }

    /**
     * Reads the datasources a role is allowed to query.
     *
     * <p>TODO(task 5.5): legacy {@code datasource_access} read; enforcement uses {@code
     * access_role_datasource} via {@code R2dbcAccessControlRepository.datasourceIds}.
     *
     * @param role the canonical role
     * @return the allowed datasource ids
     */
    public Mono<Set<String>> datasourcesForRole(String role) {
        return client.sql("SELECT datasource_id FROM datasource_access WHERE role = :role")
                .bind("role", role)
                .map((row, meta) -> row.get("datasource_id", String.class))
                .all()
                .collectList()
                .map(LinkedHashSet::new);
    }

    /** One {@code datasource_access} row. */
    public record RoleGrant(String role, String datasourceId) {}

    /**
     * Reads every role -> datasource grant.
     *
     * @return the grants
     */
    public Mono<List<RoleGrant>> grants() {
        return client.sql("SELECT role, datasource_id FROM datasource_access ORDER BY role, datasource_id")
                .map(
                        (row, meta) ->
                                new RoleGrant(
                                        row.get("role", String.class), row.get("datasource_id", String.class)))
                .all()
                .collectList();
    }

    /**
     * Grants a role access to a datasource (idempotent).
     *
     * @param role the canonical role
     * @param datasourceId the datasource id
     * @return completion
     */
    public Mono<Void> grant(String role, String datasourceId) {
        return client.sql(
                        "DELETE FROM datasource_access WHERE role = :role AND datasource_id = :datasourceId")
                .bind("role", role)
                .bind("datasourceId", datasourceId)
                .then()
                .then(
                        client.sql(
                                        "INSERT INTO datasource_access (role, datasource_id) "
                                                + "VALUES (:role, :datasourceId)")
                                .bind("role", role)
                                .bind("datasourceId", datasourceId)
                                .then());
    }

    /**
     * Revokes a role's access to a datasource.
     *
     * @param role the canonical role
     * @param datasourceId the datasource id
     * @return completion
     */
    public Mono<Void> revoke(String role, String datasourceId) {
        return client.sql(
                        "DELETE FROM datasource_access WHERE role = :role AND datasource_id = :datasourceId")
                .bind("role", role)
                .bind("datasourceId", datasourceId)
                .then();
    }
}
