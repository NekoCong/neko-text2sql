package com.nekocong.text2sql.security;

import com.nekocong.text2sql.access.AccessContextResolver;
import com.nekocong.text2sql.access.DataScopePolicy;
import com.nekocong.text2sql.access.DataScopeRule;
import com.nekocong.text2sql.access.PrincipalAttribute;
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
 * token version, an account without usable roles, or a scope whose policies cannot be evaluated is
 * rejected instead of being handed an empty or unrestricted scope.
 */
@Service
public class DataScopeResolver {

    private static final String MISSING_SCOPE = "未配置数据范围，请联系管理员";

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
        return access.resolve(userId, tokenVersion)
                .map(DataScope::from)
                .map(DataScopeResolver::requireUsable);
    }

    /**
     * Rejects a scope that carries no usable data policy, or whose {@code FILTER} rules reference a
     * principal value the caller does not have. Such a configuration cannot be evaluated, so the
     * request is denied instead of being run against a wider (or empty) result set.
     *
     * @param scope the projected scope
     * @return the unchanged scope when usable
     */
    private static DataScope requireUsable(DataScope scope) {
        if (scope.bypassesDataScope()) {
            return scope;
        }
        if (scope.access() == null || scope.access().dataScopes().isEmpty()) {
            throw denied(MISSING_SCOPE);
        }
        for (DataScopeRule rule : scope.access().dataScopes()) {
            if (rule.policy() == DataScopePolicy.FILTER && !hasPrincipalValue(scope, rule)) {
                throw denied(MISSING_SCOPE);
            }
        }
        return scope;
    }

    private static boolean hasPrincipalValue(DataScope scope, DataScopeRule rule) {
        return PrincipalAttribute.fromCode(rule.principalAttribute())
                .map(
                        attribute ->
                                switch (attribute) {
                                    case CLASS_IDS -> !scope.classIds().isEmpty();
                                    case STUDENT_ID -> scope.studentId() != null;
                                    case STUDENT_NO -> notBlank(scope.studentNo());
                                    case USER_ID -> notBlank(scope.userId());
                                    case USERNAME -> notBlank(username(scope));
                                })
                .orElse(false);
    }

    private static String username(DataScope scope) {
        return scope.access() == null || scope.access().principal() == null
                ? null
                : scope.access().principal().username();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static ResponseStatusException denied(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
