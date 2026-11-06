package com.nekocong.text2sql.controller;

import com.nekocong.text2sql.knowledge.DocumentInfo;
import com.nekocong.text2sql.service.KnowledgeService;
import com.nekocong.text2sql.knowledge.SearchHit;
import com.nekocong.text2sql.controller.dto.KnowledgeDocumentRequest;
import com.nekocong.text2sql.controller.dto.KnowledgeSearchRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Legacy in-memory knowledge-base API: ingest documents, list them, delete them, and search.
 *
 * <p>Superseded by {@link com.nekocong.text2sql.knowledge.api.KnowledgeAdminController} (the
 * persistent three-type knowledge base). Kept under {@code /legacy} so the old contract keeps
 * working while no client depends on the new paths.
 */
@RestController
@RequestMapping("/api/text2sql/knowledge/legacy")
public class KnowledgeController {

    private final KnowledgeService knowledge;

    /**
     * Creates the controller.
     *
     * @param knowledge the knowledge service
     */
    public KnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    /**
     * Ingests or replaces a document.
     *
     * @param request the document
     * @return the document info
     */
    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<DocumentInfo> ingest(@RequestBody KnowledgeDocumentRequest request) {
        return Mono.fromCallable(
                        () -> knowledge.ingest(request.namespace(), request.name(), request.content()))
                .onErrorMap(
                        IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    /**
     * Lists documents in a namespace.
     *
     * @param namespace the namespace
     * @return the documents
     */
    @GetMapping("/documents")
    public Mono<List<DocumentInfo>> list(@RequestParam(required = false) String namespace) {
        return Mono.fromSupplier(() -> knowledge.list(namespace));
    }

    /**
     * Deletes a document.
     *
     * @param namespace the namespace
     * @param name the document name
     * @return the number of chunks removed
     */
    @DeleteMapping("/documents")
    public Mono<Map<String, Integer>> delete(
            @RequestParam(required = false) String namespace, @RequestParam String name) {
        return Mono.fromSupplier(() -> Map.of("removed", knowledge.remove(namespace, name)));
    }

    /**
     * Searches the knowledge base.
     *
     * @param request the query
     * @return the ranked hits
     */
    @PostMapping("/search")
    public Mono<List<SearchHit>> search(@RequestBody KnowledgeSearchRequest request) {
        return Mono.fromSupplier(
                () -> knowledge.search(request.namespace(), request.query(), request.topK()));
    }
}
