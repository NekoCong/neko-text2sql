package com.nekocong.text2sql.knowledge;

import java.time.Instant;

/**
 * The original document behind a knowledge item, plus where its bytes and derived parse output live.
 *
 * @param id the document id
 * @param itemId the owning knowledge item
 * @param sourceType upload or URL
 * @param sourceUrl the source URL (URL imports only)
 * @param filename the original filename
 * @param mimeType the detected media type
 * @param sizeBytes the original size
 * @param sha256 the content hash used for deduplication
 * @param objectKey the MinIO key of the original
 * @param derivedPrefix the MinIO prefix of derived artifacts
 * @param pageCount the parsed page count, or {@code null}
 * @param parseEngine the engine that parsed it
 * @param parseEngineVersion the engine version
 * @param parseStatus the parse state
 * @param parseFailureReason a readable failure reason
 * @param fetchedAt when a URL was fetched
 * @param createdAt creation timestamp
 */
public record KnowledgeDocument(
        String id,
        String itemId,
        SourceType sourceType,
        String sourceUrl,
        String filename,
        String mimeType,
        long sizeBytes,
        String sha256,
        String objectKey,
        String derivedPrefix,
        Integer pageCount,
        String parseEngine,
        String parseEngineVersion,
        ParseStatus parseStatus,
        String parseFailureReason,
        Instant fetchedAt,
        Instant createdAt) {

    /** @return a copy with the given parse state */
    public KnowledgeDocument withParse(
            ParseStatus status, String engine, String version, Integer pages, String reason) {
        return new KnowledgeDocument(
                id,
                itemId,
                sourceType,
                sourceUrl,
                filename,
                mimeType,
                sizeBytes,
                sha256,
                objectKey,
                derivedPrefix,
                pages,
                engine,
                version,
                status,
                reason,
                fetchedAt,
                createdAt);
    }
}
