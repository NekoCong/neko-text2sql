package com.nekocong.text2sql.controller;

import com.nekocong.text2sql.controller.dto.ModelConfigRequest;
import com.nekocong.text2sql.controller.dto.ModelConfigView;
import com.nekocong.text2sql.service.ModelConfigCommand;
import com.nekocong.text2sql.service.ModelConfigService;
import com.nekocong.text2sql.service.ModelTestResult;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Controller layer for per-user model configuration. Delegates to {@link ModelConfigService}.
 *
 * <p>The API key is stored server-side and never returned ({@code hasApiKey} signals its presence).
 */
@RestController
@RequestMapping("/api/model-configs")
public class ModelConfigController {

    private final ModelConfigService service;

    /**
     * Creates the controller.
     *
     * @param service the model-config service
     */
    public ModelConfigController(ModelConfigService service) {
        this.service = service;
    }

    /**
     * Lists the current user's model configs.
     *
     * @param jwt the bearer token
     * @return the configs
     */
    @GetMapping
    public Mono<List<ModelConfigView>> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt.getSubject()).map(ModelConfigView::of).collectList();
    }

    /**
     * Creates a model config.
     *
     * @param jwt the bearer token
     * @param request the config
     * @return the created config
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ModelConfigView> create(
            @AuthenticationPrincipal Jwt jwt, @RequestBody ModelConfigRequest request) {
        return service.create(jwt.getSubject(), toCommand(request)).map(ModelConfigView::of);
    }

    /**
     * Updates a model config.
     *
     * @param jwt the bearer token
     * @param id the config id
     * @param request the new values
     * @return the updated config
     */
    @PutMapping("/{id}")
    public Mono<ModelConfigView> update(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody ModelConfigRequest request) {
        return service.update(jwt.getSubject(), id, toCommand(request)).map(ModelConfigView::of);
    }

    /**
     * Deletes a model config.
     *
     * @param jwt the bearer token
     * @param id the config id
     * @return an empty mono
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.delete(jwt.getSubject(), id);
    }

    /**
     * Tests connectivity for a model config.
     *
     * @param jwt the bearer token
     * @param id the config id
     * @return the test result
     */
    @PostMapping("/{id}/test")
    public Mono<ModelTestResult> test(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.test(jwt.getSubject(), id);
    }

    private static ModelConfigCommand toCommand(ModelConfigRequest request) {
        return new ModelConfigCommand(
                request.name(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.baseUrl(),
                request.isDefault());
    }
}
