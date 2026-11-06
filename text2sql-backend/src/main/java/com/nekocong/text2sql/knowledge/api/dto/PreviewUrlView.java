package com.nekocong.text2sql.knowledge.api.dto;

import java.time.Instant;

/**
 * A short-lived presigned URL for the original document.
 *
 * @param url the presigned URL
 * @param expiresAt when it expires
 */
public record PreviewUrlView(String url, Instant expiresAt) {
}
