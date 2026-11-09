package com.nekocong.text2sql.controller;

import com.nekocong.text2sql.security.SqlPermissionException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Maps permission failures raised outside the SSE path onto HTTP 403 with a readable reason.
 *
 * <p>The SSE endpoints already turn errors into an {@code error} event, so this advice mainly covers
 * the synchronous endpoints (and keeps a permission problem from surfacing as a bare 500). It also
 * renders a deliberate {@link ResponseStatusException} raised by a controller or service as
 * {@code {"status": ..., "message": ...}}, the same shape Spring's default handler uses.
 *
 * <p>Status exceptions raised in a filter (before handler mapping) never reach this advice: Spring no
 * longer routes them through the handler-exception resolvers, so the access-status filter's own JSON
 * body is preserved.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Handles a rejected statement or datasource.
     *
     * @param exception the failure
     * @return a 403 response carrying the reason
     */
    @ExceptionHandler(SqlPermissionException.class)
    public ResponseEntity<Map<String, String>> handlePermission(SqlPermissionException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Forbidden", "message", exception.getMessage()));
    }

    /**
     * Renders a deliberate status exception raised by a controller or service.
     *
     * @param exception the failure
     * @return the mapped response
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException exception) {
        HttpStatus resolved = HttpStatus.resolve(exception.getStatusCode().value());
        if (resolved == null) {
            resolved = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String reason = exception.getReason();
        String message = reason == null || reason.isBlank() ? resolved.getReasonPhrase() : reason;
        return ResponseEntity.status(resolved)
                .body(Map.of("status", resolved.value(), "message", message));
    }
}
