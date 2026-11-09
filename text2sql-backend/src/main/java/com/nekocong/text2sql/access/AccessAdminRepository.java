package com.nekocong.text2sql.access;

import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Write access to the configurable access-control tables used by the admin API: role CRUD and the
 * audit trail.
 *
 * <p>Kept separate from {@link AccessControlRepository}, which stays read-only for resolution.
 */
public interface AccessAdminRepository {

    /**
     * Lists every role, ordered by code.
     *
     * @return all roles
     */
    Mono<List<AccessRole>> roles();

    /**
     * Finds one role by id.
     *
     * @param id the role id
     * @return the role, or empty
     */
    Mono<AccessRole> role(String id);

    /**
     * Finds one role by code.
     *
     * @param code the role code
     * @return the role, or empty
     */
    Mono<AccessRole> roleByCode(String code);

    /**
     * Inserts a role with an app-generated id.
     *
     * @param code the code
     * @param name the display name
     * @param description the description, or {@code null}
     * @param parentId the parent role, or {@code null}
     * @param enabled whether the role participates in effective access
     * @return the inserted role
     */
    Mono<AccessRole> insertRole(
            String code, String name, String description, String parentId, boolean enabled);

    /**
     * Updates the mutable fields of a role ({@code code}, {@code built_in} and ids are untouched).
     *
     * @param id the role id
     * @param name the display name
     * @param description the description, or {@code null}
     * @param parentId the parent role, or {@code null}
     * @param enabled whether the role participates in effective access
     * @return completion
     */
    Mono<Void> updateRole(
            String id, String name, String description, String parentId, boolean enabled);

    /**
     * Appends one audit row.
     *
     * @param actorId the acting user id
     * @param action the action key ({@code role.create}, {@code role.update}, {@code role.disable})
     * @param entityType the entity type ({@code ROLE})
     * @param entityId the affected role id, or {@code null}
     * @param beforeJson the serialized before state, or {@code null}
     * @param afterJson the serialized after state, or {@code null}
     * @param result {@code SUCCESS} or {@code DENIED}
     * @param reason the human-readable reason, or {@code null}
     * @return completion
     */
    Mono<Void> record(
            String actorId,
            String action,
            String entityType,
            String entityId,
            String beforeJson,
            String afterJson,
            String result,
            String reason);
}
