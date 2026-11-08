package com.nekocong.text2sql.controller;

import com.nekocong.text2sql.controller.dto.DataSourceRequest;
import com.nekocong.text2sql.controller.dto.DataSourceView;
import com.nekocong.text2sql.datasource.ConnectionTestResult;
import com.nekocong.text2sql.security.DataScopeResolver;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.security.TokenClaims;
import com.nekocong.text2sql.service.DataSourceService;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
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
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Controller layer for datasource management. Delegates to {@link DataSourceService}.
 *
 * <p>Blocking pool construction / connection tests run on a bounded-elastic scheduler. Passwords are
 * never returned. Runtime-registered datasources are in-memory only.
 */
@RestController
@RequestMapping("/api/text2sql/datasources")
public class DataSourceController {

    private final DataSourceService service;
    private final DataScopeResolver scopes;

    /**
     * Creates the controller.
     *
     * @param service the datasource service
     * @param scopes resolves the caller's data scope
     */
    public DataSourceController(DataSourceService service, DataScopeResolver scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    /**
     * Lists the datasources the caller may use (without passwords).
     *
     * @param jwt the bearer token
     * @return the descriptors
     */
    @GetMapping
    public Mono<List<DataSourceView>> list(@AuthenticationPrincipal Jwt jwt) {
        return scopes
                .resolve(jwt.getSubject(), jwt.getClaimAsString(Roles.CLAIM), TokenClaims.tokenVersion(jwt))
                .map(
                        scope -> {
                            String defaultId = service.defaultId();
                            return service.list().stream()
                                    .filter(
                                            definition ->
                                                    scope.isAdmin()
                                                            || scope.allowedDatasources()
                                                                    .contains(definition.getId()))
                                    .map(definition -> DataSourceView.of(definition, defaultId))
                                    .toList();
                        });
    }

    /**
     * Registers a new datasource.
     *
     * @param request the datasource definition
     * @return the created descriptor
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<DataSourceView> add(@RequestBody DataSourceRequest request) {
        return guarded(() -> DataSourceView.of(service.register(request.toDefinition()), service.defaultId()));
    }

    /**
     * Replaces an existing datasource.
     *
     * @param id the datasource id
     * @param request the new definition
     * @return the updated descriptor
     */
    @PutMapping("/{id}")
    public Mono<DataSourceView> update(
            @PathVariable String id, @RequestBody DataSourceRequest request) {
        return guarded(() -> DataSourceView.of(service.replace(id, request.toDefinition()), service.defaultId()));
    }

    /**
     * Removes a datasource.
     *
     * @param id the datasource id
     * @return an empty mono
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> remove(@PathVariable String id) {
        return guardedVoid(() -> service.remove(id));
    }

    /**
     * Tests a registered datasource.
     *
     * @param id the datasource id
     * @return the test result
     */
    @PostMapping("/{id}/test")
    public Mono<ConnectionTestResult> test(@PathVariable String id) {
        return guarded(() -> service.test(id));
    }

    /**
     * Tests a candidate datasource without registering it.
     *
     * @param request the candidate definition
     * @return the test result
     */
    @PostMapping("/test")
    public Mono<ConnectionTestResult> testCandidate(@RequestBody DataSourceRequest request) {
        return guarded(() -> service.test(request.toDefinition()));
    }

    private <T> Mono<T> guarded(Supplier<T> action) {
        return Mono.fromCallable(action::get)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        NoSuchElementException.class,
                        ex -> new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage()))
                .onErrorMap(
                        IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    private Mono<Void> guardedVoid(Runnable action) {
        return Mono.fromRunnable(action)
                .subscribeOn(Schedulers.boundedElastic())
                .then()
                .onErrorMap(
                        NoSuchElementException.class,
                        ex -> new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage()))
                .onErrorMap(
                        IllegalStateException.class,
                        ex -> new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()));
    }
}
