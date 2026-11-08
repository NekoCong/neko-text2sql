package com.nekocong.text2sql.service;

import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.controller.dto.DatasourceAccessMatrix;
import com.nekocong.text2sql.controller.dto.DatasourceAccessView;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import com.nekocong.text2sql.repository.AccessRepository;
import com.nekocong.text2sql.repository.AccessRepository.RoleGrant;
import com.nekocong.text2sql.security.Roles;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Maintains the "role -> datasource" whitelist shown on the admin page.
 *
 * <p>Only student/counselor are grantable: admins are unrestricted by definition and therefore have
 * no rows. Unknown roles and unknown datasources are rejected so a typo cannot silently grant
 * nothing.
 */
@Service
public class DatasourceAccessService {

    private static final List<String> GRANTABLE_ROLES = List.of(Roles.STUDENT, Roles.COUNSELOR);

    private final AccessRepository access;
    private final AccessControlRepository accessControl;
    private final DataSourceRegistry registry;

    /**
     * Creates the service.
     *
     * @param access the legacy access repository
     * @param accessControl the configurable access-control repository
     * @param registry the datasource registry
     */
    public DatasourceAccessService(
            AccessRepository access, AccessControlRepository accessControl, DataSourceRegistry registry) {
        this.access = access;
        this.accessControl = accessControl;
        this.registry = registry;
    }

    /**
     * Reads the full matrix.
     *
     * @return the matrix
     */
    public Mono<DatasourceAccessMatrix> matrix() {
        return access.grants().map(this::build);
    }

    /**
     * Grants a role access to a datasource, then returns the new matrix.
     *
     * @param role the role
     * @param datasourceId the datasource id
     * @return the matrix
     */
    public Mono<DatasourceAccessMatrix> grant(String role, String datasourceId) {
        String canonical = requireRole(role);
        requireDatasource(datasourceId);
        return access.grant(canonical, datasourceId)
                .then(accessControl.grantDatasourceByCode(canonical, datasourceId))
                .then(matrix());
    }

    /**
     * Revokes a role's access to a datasource, then returns the new matrix.
     *
     * @param role the role
     * @param datasourceId the datasource id
     * @return the matrix
     */
    public Mono<DatasourceAccessMatrix> revoke(String role, String datasourceId) {
        String canonical = requireRole(role);
        requireDatasource(datasourceId);
        return access.revoke(canonical, datasourceId)
                .then(accessControl.revokeDatasourceByCode(canonical, datasourceId))
                .then(matrix());
    }

    private DatasourceAccessMatrix build(List<RoleGrant> grants) {
        String defaultId = registry.defaultId();
        List<DatasourceAccessView> datasources =
                registry.definitions().stream()
                        .map(
                                definition ->
                                        new DatasourceAccessView(
                                                definition.getId(),
                                                definition.getName(),
                                                definition.getId().equals(defaultId),
                                                grantedRoles(grants, definition.getId())))
                        .toList();
        return new DatasourceAccessMatrix(GRANTABLE_ROLES, datasources);
    }

    private static List<String> grantedRoles(List<RoleGrant> grants, String datasourceId) {
        Set<String> roles =
                grants.stream()
                        .filter(grant -> datasourceId.equals(grant.datasourceId()))
                        .map(RoleGrant::role)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return List.copyOf(roles);
    }

    private static String requireRole(String role) {
        String canonical = Roles.normalize(role);
        if (canonical == null || !GRANTABLE_ROLES.contains(canonical)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该角色不可授权数据源");
        }
        return canonical;
    }

    private void requireDatasource(String datasourceId) {
        try {
            registry.definition(datasourceId);
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据源不存在");
        }
    }
}
