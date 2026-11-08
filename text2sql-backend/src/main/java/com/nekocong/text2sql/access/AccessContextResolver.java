package com.nekocong.text2sql.access;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Resolves the caller's effective access from the database on every request.
 *
 * <p>The JWT only proves identity. Roles, permissions, datasource grants and data scopes come from
 * the access-control tables, so an administrative change applies to the next request. A disabled
 * account, a stale token version, an unknown account or an account without usable roles fails
 * closed.
 */
@Service
public class AccessContextResolver {

    private final AccessControlRepository repository;

    /**
     * Creates the resolver.
     *
     * @param repository the access-control repository
     */
    public AccessContextResolver(AccessControlRepository repository) {
        this.repository = repository;
    }

    /**
     * Resolves the caller's effective access.
     *
     * @param userId the authenticated user id
     * @param tokenVersion the {@code ver} claim of the presented token (must match the account)
     * @return the effective access
     */
    public Mono<EffectiveAccess> resolve(String userId, Integer tokenVersion) {
        return repository
                .account(userId)
                .switchIfEmpty(Mono.error(unauthorized("登录状态已失效，请重新登录")))
                .flatMap(account -> {
                    if (!account.enabled()) {
                        return Mono.error(unauthorized("账号已停用，请联系管理员"));
                    }
                    if (tokenVersion == null || tokenVersion != account.tokenVersion()) {
                        return Mono.error(unauthorized("登录状态已失效，请重新登录"));
                    }
                    return Mono.zip(repository.rolesOf(userId), repository.allRoles())
                            .flatMap(roles -> {
                                List<AccessRole> effective =
                                        effectiveRoles(roles.getT1(), roles.getT2());
                                if (effective.isEmpty()) {
                                    return Mono.error(denied("当前账号没有可用角色，请联系管理员"));
                                }
                                List<String> roleIds =
                                        effective.stream().map(AccessRole::id).toList();
                                return Mono.zip(
                                                repository.permissionKeys(roleIds),
                                                repository.datasourceIds(roleIds),
                                                repository.dataScopes(roleIds),
                                                repository.principal(userId, account.username()))
                                        .map(tuple -> new EffectiveAccess(
                                                userId,
                                                effective,
                                                tuple.getT1(),
                                                tuple.getT2(),
                                                tuple.getT3(),
                                                tuple.getT4()));
                            });
                });
    }

    /**
     * Computes enabled direct roles plus their enabled ancestors, rejecting cycles.
     */
    private static List<AccessRole> effectiveRoles(
            List<AccessRole> direct, List<AccessRole> all) {
        Map<String, AccessRole> byId = new HashMap<>();
        for (AccessRole role : all) {
            byId.put(role.id(), role);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (AccessRole role : direct) {
            if (role.enabled()) {
                addWithParents(role, byId, ids, new LinkedHashSet<>());
            }
        }
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private static void addWithParents(
            AccessRole role,
            Map<String, AccessRole> byId,
            Set<String> effective,
            Set<String> path) {
        if (!path.add(role.id())) {
            throw denied("角色继承配置存在环，拒绝访问");
        }
        effective.add(role.id());
        if (role.parentId() != null) {
            AccessRole parent = byId.get(role.parentId());
            if (parent != null && parent.enabled()) {
                addWithParents(parent, byId, effective, path);
            }
        }
        path.remove(role.id());
    }

    private static ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }

    private static ResponseStatusException denied(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
