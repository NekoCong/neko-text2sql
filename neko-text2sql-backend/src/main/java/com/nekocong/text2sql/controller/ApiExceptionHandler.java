package com.nekocong.text2sql.controller;

import com.nekocong.text2sql.security.SqlPermissionException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps permission failures raised outside the SSE path onto HTTP 403 with a readable reason.
 *
 * <p>The SSE endpoints already turn errors into an {@code error} event, so this advice mainly covers
 * the synchronous endpoints (and keeps a permission problem from surfacing as a bare 500).
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
}
