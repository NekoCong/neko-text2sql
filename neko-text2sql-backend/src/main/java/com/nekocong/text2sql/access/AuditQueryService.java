package com.nekocong.text2sql.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.api.dto.AccessAuditPageView;
import com.nekocong.text2sql.access.api.dto.AccessAuditView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * The read-only audit API: paginated access to the {@code access_audit} trail.
 *
 * <p>Reads are never audited. Every filter is optional and bound as a parameter — the query is built
 * from static fragments only, never from client input, so a hostile filter cannot change the SQL.
 *
 * <ul>
 *   <li>{@code actorId}, {@code entityId}, {@code action}, {@code entityType}: exact match; an unknown
 *       value simply matches nothing.
 *   <li>{@code result}: {@code SUCCESS} or {@code DENIED}; anything else is a {@code 400}.
 *   <li>{@code from}/{@code to}: epoch millis, {@code from} inclusive, {@code to} exclusive; {@code
 *       from} after {@code to} is a {@code 400}.
 *   <li>Pagination: {@code page} defaults to {@code 0}, {@code size} to {@code 50} (capped at {@value
 *       #MAX_PAGE_SIZE}); a negative page or a non-positive size is a {@code 400}. Ordered by {@code
 *       created_at DESC, id DESC}, so ties in the same millisecond still have a deterministic order.
 * </ul>
 *
 * <p>The stored {@code before_json}/{@code after_json} text is parsed with Jackson back into JSON for
 * the client; a malformed stored payload (the writers never produce one) is echoed as its raw string
 * rather than failing the read.
 */
@Service
public class AuditQueryService {

    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_DENIED = "DENIED";

    /** Upper bound on the audit page size; larger requests are capped, not rejected. */
    private static final int MAX_PAGE_SIZE = 200;

    private static final String REASON_RESULT_INVALID = "结果过滤值无效";
    private static final String REASON_TIME_RANGE_INVALID = "时间范围无效";
    private static final String REASON_PAGE_INVALID = "分页参数无效";

    private final AccessAdminRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service.
     *
     * @param repository the admin repository
     * @param objectMapper the shared Jackson mapper, used to parse the stored audit JSON
     */
    public AuditQueryService(AccessAdminRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists the audit trail, newest first, paginated.
     *
     * @param actorId the exact actor filter, or {@code null}
     * @param action the exact action filter, or {@code null}
     * @param entityType the exact entity-type filter, or {@code null}
     * @param entityId the exact entity-id filter, or {@code null}
     * @param result {@code SUCCESS}/{@code DENIED}, or {@code null}
     * @param from the inclusive lower time bound (epoch millis) as text, or {@code null}
     * @param to the exclusive upper time bound (epoch millis) as text, or {@code null}
     * @param page the zero-based page number (must be at least 0)
     * @param size the requested page size (must be at least 1)
     * @return the page of entries with the total count
     */
    public Mono<AccessAuditPageView> list(
            String actorId,
            String action,
            String entityType,
            String entityId,
            String result,
            String from,
            String to,
            int page,
            int size) {
        return Mono.defer(
                () -> {
                    int limit = validatePage(page, size);
                    long offset = (long) page * limit;
                    AuditQuery query =
                            validateQuery(actorId, action, entityType, entityId, result, from, to);
                    return Mono.zip(
                                    repository.audit(query, limit, offset),
                                    repository.countAudit(query))
                            .map(
                                    tuple -> {
                                        List<AccessAuditView> items =
                                                tuple.getT1().stream().map(this::toView).toList();
                                        return new AccessAuditPageView(
                                                items, tuple.getT2(), page, limit);
                                    });
                });
    }

    /** Maps one raw row to its view, parsing the stored JSON payloads. */
    private AccessAuditView toView(AccessAudit audit) {
        return new AccessAuditView(
                audit.id(),
                audit.actorId(),
                audit.action(),
                audit.entityType(),
                audit.entityId(),
                parseJson(audit.beforeJson()),
                parseJson(audit.afterJson()),
                audit.result(),
                audit.reason(),
                audit.createdAt());
    }

    /**
     * Parses a stored payload back to a JSON value (an object, array, scalar, or {@code null}). A
     * malformed payload (never produced by the writers) is returned as its raw string so one bad row
     * cannot fail the whole read.
     */
    private Object parseJson(String text) {
        if (text == null) {
            return null;
        }
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (JsonProcessingException ex) {
            return text;
        }
    }

    /** Validates the filters and returns the normalized query; unknown filters are dropped. */
    private static AuditQuery validateQuery(
            String actorId,
            String action,
            String entityType,
            String entityId,
            String result,
            String from,
            String to) {
        String resultValue = normalizeResult(result);
        Long fromValue = parseEpoch(from);
        Long toValue = parseEpoch(to);
        if (fromValue != null && toValue != null && fromValue > toValue) {
            throw badRequest(REASON_TIME_RANGE_INVALID);
        }
        return new AuditQuery(
                blankToNull(actorId),
                blankToNull(action),
                blankToNull(entityType),
                blankToNull(entityId),
                resultValue,
                fromValue,
                toValue);
    }

    private static String normalizeResult(String result) {
        if (result == null || result.isBlank()) {
            return null;
        }
        String value = result.trim();
        if (!RESULT_SUCCESS.equals(value) && !RESULT_DENIED.equals(value)) {
            throw badRequest(REASON_RESULT_INVALID);
        }
        return value;
    }

    private static Long parseEpoch(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw badRequest(REASON_TIME_RANGE_INVALID);
        }
    }

    /**
     * Validates the page number and size, returning the (capped) effective size. A negative page or a
     * non-positive size is a {@code 400}; a size larger than {@link #MAX_PAGE_SIZE} is capped.
     */
    private static int validatePage(int page, int size) {
        if (page < 0 || size <= 0) {
            throw badRequest(REASON_PAGE_INVALID);
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
