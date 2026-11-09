package com.nekocong.text2sql.controller;

import com.nekocong.text2sql.controller.dto.DatasourceAccessMatrix;
import com.nekocong.text2sql.service.DatasourceAccessService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * <p>Administrator endpoints for the role -> datasource whitelist.
 *
 * <p>Access is enforced by {@code SecurityConfig} ({@code /api/admin/**} requires the {@code
 * access.manage} permission resolved from the access-control tables), so an unauthorized caller
 * never reaches these handlers.
 */
@RestController
@RequestMapping("/api/admin/datasource-access")
public class DatasourceAccessController {

    private final DatasourceAccessService service;

    /**
     * Creates the controller.
     *
     * @param service the access service
     */
    public DatasourceAccessController(DatasourceAccessService service) {
        this.service = service;
    }

    /**
     * Reads the access matrix.
     *
     * @return the matrix
     */
    @GetMapping
    public Mono<DatasourceAccessMatrix> matrix() {
        return service.matrix();
    }

    /**
     * Grants a role access to a datasource.
     *
     * @param role the role
     * @param datasourceId the datasource id
     * @return the updated matrix
     */
    @PutMapping("/{role}/{datasourceId}")
    public Mono<DatasourceAccessMatrix> grant(
            @PathVariable String role, @PathVariable String datasourceId) {
        return service.grant(role, datasourceId);
    }

    /**
     * Revokes a role's access to a datasource.
     *
     * @param role the role
     * @param datasourceId the datasource id
     * @return the updated matrix
     */
    @DeleteMapping("/{role}/{datasourceId}")
    public Mono<DatasourceAccessMatrix> revoke(
            @PathVariable String role, @PathVariable String datasourceId) {
        return service.revoke(role, datasourceId);
    }
}
