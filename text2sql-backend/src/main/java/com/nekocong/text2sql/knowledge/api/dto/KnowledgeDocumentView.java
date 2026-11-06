package com.nekocong.text2sql.knowledge.api.dto;

import com.nekocong.text2sql.knowledge.KnowledgeDocument;
import com.nekocong.text2sql.knowledge.ParseStatus;
import com.nekocong.text2sql.knowledge.SourceType;
import java.time.Instant;

/**
 * A document as returned to the admin UI.
 *
 * @param id the document id
 * @param itemId the owning item
 * @param sourceType upload or URL
 * @param sourceUrl the source URL (URL imports only), or {@code null}
 * @param filename the original filename
 * @param mimeType the media type
 * @param sizeBytes the original size
 * @param pageCount the parsed page count, or {@code null}
 * @param parseEngine the engine that parsed it, or {@code null}
 * @param parseEngineVersion the engine version, or {@code null}
 * @param parseStatus the parse state
 * @param parseFailureReason a readable failure reason, or {@code null}
 * @param fetchedAt when a URL was fetched, or {@code null}
 * @param createdAt creation timestamp
 * @param itemStatus the owning item's lifecycle state
 * @param itemTitle the owning item's title
 */
public record KnowledgeDocumentView(
        String id,
        String itemId,
        SourceType sourceType,
        String sourceUrl,
        String filename,
        String mimeType,
        long sizeBytes,
        Integer pageCount,
        String parseEngine,
        String parseEngineVersion,
        ParseStatus parseStatus,
        String parseFailureReason,
        Instant fetchedAt,
        Instant createdAt,
        String itemStatus,
        String itemTitle) {

    /**
     * Maps a document.
     *
     * @param document the document
     * @param itemStatus the owning item's status name, or {@code null}
     * @param itemTitle the owning item's title, or {@code null}
     * @return the view
     */
    public static KnowledgeDocumentView of(
            KnowledgeDocument document, String itemStatus, String itemTitle) {
        return new KnowledgeDocumentView(
                document.id(),
                document.itemId(),
                document.sourceType(),
                document.sourceUrl(),
                document.filename(),
                document.mimeType(),
                document.sizeBytes(),
                document.pageCount(),
                document.parseEngine(),
                document.parseEngineVersion(),
                document.parseStatus(),
                document.parseFailureReason(),
                document.fetchedAt(),
                document.createdAt(),
                itemStatus,
                itemTitle);
    }
}
