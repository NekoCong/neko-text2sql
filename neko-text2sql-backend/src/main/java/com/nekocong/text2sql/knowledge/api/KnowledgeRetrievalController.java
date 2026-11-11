package com.nekocong.text2sql.knowledge.api;

import com.nekocong.text2sql.config.BlockingSchedulerConfig;
import com.nekocong.text2sql.knowledge.SearchResult;
import com.nekocong.text2sql.knowledge.api.dto.KnowledgeQueryRequest;
import com.nekocong.text2sql.knowledge.service.KnowledgeSearchService;
import com.nekocong.text2sql.security.DataScopeResolver;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.security.TokenClaims;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Retrieval API used by the admin search-debug panel.
 *
 * <p>Both endpoints run the exact production retrieval chain (rewrite → dual recall → expansion →
 * selection) and return the explainable trace, so what the administrator sees is what the pipeline
 * would use.
 */
@RestController
@RequestMapping("/api/text2sql/knowledge")
@ConditionalOnProperty(prefix = "text2sql.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeRetrievalController {

    private final KnowledgeSearchService search;
    private final DataScopeResolver scopes;
    private final Scheduler blockingScheduler;

    /**
     * Creates the controller.
     *
     * @param search the retrieval service
     * @param scopes resolves the caller's data scope
     * @param blockingScheduler the virtual-thread scheduler for blocking retrieval
     */
    public KnowledgeRetrievalController(
            KnowledgeSearchService search,
            DataScopeResolver scopes,
            @Qualifier(BlockingSchedulerConfig.BLOCKING_SCHEDULER) Scheduler blockingScheduler) {
        this.search = search;
        this.scopes = scopes;
        this.blockingScheduler = blockingScheduler;
    }

    /**
     * Runs a retrieval.
     *
     * @param request the query
     * @param jwt the bearer token
     * @return the hits, citations and trace
     */
    @PostMapping("/search")
    public Mono<SearchResult> search(
            @RequestBody KnowledgeQueryRequest request, @AuthenticationPrincipal Jwt jwt) {
        return execute(request, jwt);
    }

    /**
     * Runs a retrieval and returns the same explainable trace (alias of {@code /search}).
     *
     * @param request the query
     * @param jwt the bearer token
     * @return the hits, citations and trace
     */
    @PostMapping("/search/debug")
    public Mono<SearchResult> debug(
            @RequestBody KnowledgeQueryRequest request, @AuthenticationPrincipal Jwt jwt) {
        return execute(request, jwt);
    }

    private Mono<SearchResult> execute(KnowledgeQueryRequest request, Jwt jwt) {
        return scopes
                .resolve(
                        jwt.getSubject(),
                        jwt.getClaimAsString(Roles.CLAIM),
                        TokenClaims.tokenVersion(jwt))
                .flatMap(scope -> Mono.fromCallable(() -> search.search(
                                scope,
                                request.datasourceId(),
                                request.agentId(),
                                request.query(),
                                request.topK()))
                        .subscribeOn(blockingScheduler));
    }
}
