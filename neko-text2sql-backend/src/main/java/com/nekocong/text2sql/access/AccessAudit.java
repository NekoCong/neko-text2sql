package com.nekocong.text2sql.access;

/**
 * One access-control audit row as read back from {@code access_audit}.
 *
 * <p>The JSON payloads stay as raw text here; parsing into structured JSON (for the API view) is the
 * service's job, so a malformed stored row can be tolerated on read instead of failing the whole
 * page.
 *
 * @param id the row id
 * @param actorId the acting user id
 * @param action the action key (e.g. {@code role.update})
 * @param entityType the entity type ({@code ROLE}, {@code USER}, {@code DATA_SCOPE})
 * @param entityId the affected id, or {@code null}
 * @param beforeJson the serialized before state, or {@code null}
 * @param afterJson the serialized after state, or {@code null}
 * @param result {@code SUCCESS} or {@code DENIED}
 * @param reason the human-readable reason, or {@code null}
 * @param createdAt the record time (epoch millis)
 */
public record AccessAudit(
        String id,
        String actorId,
        String action,
        String entityType,
        String entityId,
        String beforeJson,
        String afterJson,
        String result,
        String reason,
        long createdAt) {}
