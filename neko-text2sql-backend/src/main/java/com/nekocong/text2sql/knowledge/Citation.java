package com.nekocong.text2sql.knowledge;

import java.time.Instant;
import java.util.List;

/**
 * The evidence attached to a retrieval hit: where in the original document it came from.
 *
 * @param documentId the document id
 * @param filename the original filename
 * @param mimeType the media type (drives the preview mode on the client)
 * @param sourceType upload or URL
 * @param pageNo the 1-based page number, or {@code null}
 * @param bbox the normalized box, or {@code null}
 * @param charStart the start offset in the text snapshot, or {@code null}
 * @param charEnd the end offset in the text snapshot, or {@code null}
 * @param quote the quoted original text
 * @param previewUrl a short-lived preview URL, or {@code null} when unavailable
 * @param previewExpiresAt when {@link #previewUrl} expires
 * @param fetchedAt when a URL snapshot was taken
 */
public record Citation(
        String documentId,
        String filename,
        String mimeType,
        String sourceType,
        Integer pageNo,
        List<Integer> bbox,
        Integer charStart,
        Integer charEnd,
        String quote,
        String previewUrl,
        Instant previewExpiresAt,
        Instant fetchedAt) {
}
