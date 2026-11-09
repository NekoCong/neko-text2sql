package com.nekocong.text2sql.access.api.dto;

/**
 * One access-control audit entry as returned to admin clients.
 *
 * <p>{@code before}/{@code after} are the stored JSON payloads parsed back to JSON values (an object,
 * an array, a scalar, or {@code null}). A malformed stored payload — which the writers never produce,
 * but a manual edit or migration could — is returned as its raw string rather than failing the read;
 * this is deliberate, so a single bad row cannot break the whole page.
 *
 * <p>Deliberately free of password material: the writers store only projections of the affected
 * entity, never hashes or tokens.
 *
 * @param id the row id
 * @param actorId the acting user id
 * @param action the action key
 * @param entityType the entity type
 * @param entityId the affected id, or {@code null}
 * @param before the parsed before state, or {@code null}
 * @param after the parsed after state, or {@code null}
 * @param result {@code SUCCESS} or {@code DENIED}
 * @param reason the human-readable reason, or {@code null}
 * @param createdAt the record time (epoch millis)
 */
public record AccessAuditView(
        String id,
        String actorId,
        String action,
        String entityType,
        String entityId,
        Object before,
        Object after,
        String result,
        String reason,
        long createdAt) {}
