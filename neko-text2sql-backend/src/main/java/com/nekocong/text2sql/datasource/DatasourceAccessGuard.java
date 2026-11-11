package com.nekocong.text2sql.datasource;

import com.nekocong.text2sql.security.DataScope;
import com.nekocong.text2sql.security.SqlPermissionException;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides which datasource a request may run against.
 *
 * <p>A non-admin may only use datasources granted to its role; when the request omits the
 * datasource, the configured default is used only if it is granted, otherwise the first granted id
 * (sorted, so the choice is deterministic). A role with no grant at all fails instead of falling
 * back to the global default.
 */
@Component
public class DatasourceAccessGuard {

    private final DataSourceRegistry registry;

    /**
     * Creates the guard.
     *
     * @param registry the datasource registry
     */
    public DatasourceAccessGuard(DataSourceRegistry registry) {
        this.registry = registry;
    }

    /**
     * Resolves and authorizes the datasource for a request.
     *
     * @param scope the caller's scope
     * @param requestedId the requested datasource id, or {@code null}/blank for the default
     * @return the authorized datasource id
     * @throws SqlPermissionException when the request is not allowed
     */
    public String resolve(DataScope scope, String requestedId) {
        if (scope.isAdmin()) {
            String id = (requestedId == null || requestedId.isBlank()) ? registry.defaultId() : requestedId;
            try {
                registry.definition(id); // fail fast on an unknown id
            } catch (NoSuchElementException ex) {
                throw new SqlPermissionException("数据源 " + id + " 不存在");
            }
            return id;
        }

        Set<String> allowed = scope.allowedDatasources();
        if (allowed.isEmpty()) {
            throw new SqlPermissionException("当前角色没有任何可用的数据源");
        }

        Set<String> known =
                registry.definitions().stream()
                        .map(DataSourceDefinition::getId)
                        .filter(allowed::contains)
                        .collect(java.util.stream.Collectors.toSet());
        if (known.isEmpty()) {
            throw new SqlPermissionException("当前角色授权的数据源均不可用");
        }

        if (requestedId != null && !requestedId.isBlank()) {
            if (!allowed.contains(requestedId)) {
                throw new SqlPermissionException("数据源 " + requestedId + " 未授权");
            }
            if (!known.contains(requestedId)) {
                throw new SqlPermissionException("数据源 " + requestedId + " 已不可用");
            }
            return requestedId;
        }

        String defaultId = registry.defaultId();
        if (defaultId != null && known.contains(defaultId)) {
            return defaultId;
        }
        throw new SqlPermissionException("默认数据源未授权，请显式指定可用的数据源");
    }
}
