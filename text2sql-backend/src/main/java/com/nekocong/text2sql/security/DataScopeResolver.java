package com.nekocong.text2sql.security;

import com.nekocong.text2sql.access.AccessContextResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Resolves the data scope for a request from the caller's id and token version.
 *
 * <p>The scope is a projection of the database-configured effective access ({@link
 * AccessContextResolver}). Authorization therefore follows the account's roles and policies, not the
 * token's role claim: an account whose roles were changed after the token was issued is scoped by
 * its current roles, while an unrecognized claim still fails closed early.
 *
 * <p>Fails closed: an unrecognized role claim, a disabled or unknown account, a stale or missing
 * token version, or an account without usable roles is rejected instead of being handed an empty or
 * unrestricted scope.
 */
@Service
public class DataScopeResolver {

    private final AccessContextResolver access;

    /**
     * Creates the resolver.
     *
     * @param access the access-context resolver
     */
    public DataScopeResolver(AccessContextResolver access) {
        this.access = access;
    }

    /**
     * Resolves the caller's scope from the database-configured access.
     *
     * @param userId the authenticated user id
     * @param role the raw role claim; an unrecognized non-null claim is rejected early
     * @param tokenVersion the token's {@code ver} claim; {@code null} fails closed
     * @return the resolved scope, or an error when the account has no usable access
     */
    public Mono<DataScope> resolve(String userId, String role, Integer tokenVersion) {
        if (role != null && Roles.normalize(role) == null) {
            return Mono.error(denied("当前账号没有可识别的角色，无法确定数据范围"));
        }
        return access.resolve(userId, tokenVersion).map(DataScope::from);
    }

    /**
     * Resolves the caller's scope without a token version. Kept for sources that have not migrated;
     * it fails closed because the access resolver rejects a {@code null} token version.
     *
     * @param userId the authenticated user id
     * @param role the raw role claim
     * @return the resolved scope, or an error
     */
    public Mono<DataScope> resolve(String userId, String role) {
        return resolve(userId, role, null);
    }

    private static ResponseStatusException denied(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
