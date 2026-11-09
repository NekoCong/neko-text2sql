package com.nekocong.text2sql.access;

/**
 * The validated filter for an audit listing. Every field is optional; a {@code null} field means
 * "any". Values are pre-validated and normalized by {@link AuditQueryService}; the repository only
 * binds them, never interpolates them into SQL.
 *
 * @param actorId exact {@code actor_id} match, or {@code null}
 * @param action exact {@code action} match, or {@code null}
 * @param entityType exact {@code entity_type} match, or {@code null}
 * @param entityId exact {@code entity_id} match, or {@code null}
 * @param result {@code SUCCESS} or {@code DENIED}, or {@code null}
 * @param from inclusive lower bound on {@code created_at} (epoch millis), or {@code null}
 * @param to exclusive upper bound on {@code created_at} (epoch millis), or {@code null}
 */
public record AuditQuery(
        String actorId,
        String action,
        String entityType,
        String entityId,
        String result,
        Long from,
        Long to) {}
