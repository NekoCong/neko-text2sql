package com.nekocong.text2sql.knowledge.api;

import com.nekocong.text2sql.config.BlockingSchedulerConfig;
import com.nekocong.text2sql.knowledge.api.dto.DocumentContentView;
import com.nekocong.text2sql.knowledge.api.dto.PreviewUrlView;
import com.nekocong.text2sql.knowledge.service.KnowledgePreviewService;
import com.nekocong.text2sql.knowledge.store.ObjectStoreException;
import com.nekocong.text2sql.security.DataScopeResolver;
import com.nekocong.text2sql.security.Roles;
import java.util.NoSuchElementException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Original-source preview for citation badges.
 *
 * <p>Separate from the admin knowledge API on purpose: any authenticated caller whose answer cited
 * a document must be able to open it, while the management endpoints stay administrator-only. The
 * service still enforces the document's datasource scope before signing a URL.
 */
@RestController
@RequestMapping("/api/text2sql/citations")
@ConditionalOnProperty(prefix = "text2sql.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeCitationController {

    private final KnowledgePreviewService preview;
    private final DataScopeResolver scopes;
    private final Scheduler blockingScheduler;

    /**
     * Creates the controller.
     *
     * @param preview the preview service
     * @param scopes resolves the caller's data scope
     * @param blockingScheduler the virtual-thread scheduler for blocking MinIO reads
     */
    public KnowledgeCitationController(
            KnowledgePreviewService preview,
            DataScopeResolver scopes,
            @Qualifier(BlockingSchedulerConfig.BLOCKING_SCHEDULER) Scheduler blockingScheduler) {
        this.preview = preview;
        this.scopes = scopes;
        this.blockingScheduler = blockingScheduler;
    }

    /**
     * Signs a short-lived preview URL for a cited document.
     *
     * @param documentId the document id
     * @param jwt the bearer token
     * @return the URL and its expiry
     */
    @GetMapping("/{documentId}/preview-url")
    public Mono<PreviewUrlView> previewUrl(
            @PathVariable String documentId, @AuthenticationPrincipal Jwt jwt) {
        return scopes
                .resolve(jwt.getSubject(), jwt.getClaimAsString(Roles.CLAIM))
                .flatMap(scope -> Mono.fromCallable(() -> preview.previewUrl(documentId, scope))
                        .subscribeOn(blockingScheduler)
                        .onErrorMap(
                                NoSuchElementException.class,
                                ex -> new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, ex.getMessage()))
                        .onErrorMap(
                                ObjectStoreException.class,
                                ex -> new ResponseStatusException(
                                        HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage())));
    }

    /**
     * Returns the normalized text snapshot of a cited document.
     *
     * @param documentId the document id
     * @param jwt the bearer token
     * @return the snapshot
     */
    @GetMapping("/{documentId}/content")
    public Mono<DocumentContentView> content(
            @PathVariable String documentId, @AuthenticationPrincipal Jwt jwt) {
        return scopes
                .resolve(jwt.getSubject(), jwt.getClaimAsString(Roles.CLAIM))
                .flatMap(scope -> Mono.fromCallable(() -> preview.content(documentId, scope))
                        .subscribeOn(blockingScheduler)
                        .onErrorMap(
                                NoSuchElementException.class,
                                ex -> new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, ex.getMessage()))
                        .onErrorMap(
                                ObjectStoreException.class,
                                ex -> new ResponseStatusException(
                                        HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage())));
    }
}
