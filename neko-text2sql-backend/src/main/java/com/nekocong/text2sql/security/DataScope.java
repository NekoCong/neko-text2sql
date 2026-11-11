package com.nekocong.text2sql.security;

import com.nekocong.text2sql.access.EffectiveAccess;
import com.nekocong.text2sql.access.PrincipalAttributes;
import java.util.Set;

/**
 * The resolved data scope for one request: who the caller is, which role they carry, and the
 * concrete values the permission layer needs to filter by.
 *
 * <p>Immutable and self-contained: it is passed from the web layer into the pipeline, and (for the
 * agent path) placed into the AgentScope runtime context, so no layer has to re-read the identity.
 *
 * <p>The {@code role}, {@code classIds}, {@code studentId}, {@code studentNo} and {@code
 * allowedDatasources} components are a flat projection of the caller's {@link EffectiveAccess}, kept
 * for rendering and for callers that only need the identity. Enforcement (table visibility and row
 * predicates) is derived from {@code access}, never from the role name.
 *
 * @param userId the authenticated user id
 * @param role the first effective role code (display/diagnostics only; enforcement never branches on
 *     it)
 * @param classIds the classes in scope, projected from the principal
 * @param studentId the caller's own business-database student id, projected from the principal
 * @param studentNo the caller's own student number, projected from the principal
 * @param allowedDatasources the effective datasource grants
 * @param access the database-configured effective access; {@code null} fails closed at the enforcement
 *     chokepoints
 */
public record DataScope(
        String userId,
        String role,
        Set<Long> classIds,
        Long studentId,
        String studentNo,
        Set<String> allowedDatasources,
        EffectiveAccess access) {

    /** Normalizes the collections so the scope is structurally immutable. */
    public DataScope {
        classIds = classIds == null ? Set.of() : Set.copyOf(classIds);
        allowedDatasources = allowedDatasources == null ? Set.of() : Set.copyOf(allowedDatasources);
    }

    /**
     * Creates a scope without resolved access. Kept for source compatibility; callers constructed
     * this way fail closed at every enforcement chokepoint because no policy can be evaluated.
     *
     * @param userId the authenticated user id
     * @param role the role code
     * @param classIds the classes in scope
     * @param studentId the caller's student id
     * @param studentNo the caller's student number
     * @param allowedDatasources the datasource grants
     */
    public DataScope(
            String userId,
            String role,
            Set<Long> classIds,
            Long studentId,
            String studentNo,
            Set<String> allowedDatasources) {
        this(userId, role, classIds, studentId, studentNo, allowedDatasources, null);
    }

    /**
     * Projects the database-configured effective access into a scope.
     *
     * @param access the resolved access (may be {@code null}, which yields a scope that fails closed)
     * @return the scope carrying the access
     */
    public static DataScope from(EffectiveAccess access) {
        if (access == null) {
            return new DataScope(null, null, Set.of(), null, null, Set.of(), null);
        }
        PrincipalAttributes principal = access.principal();
        String role = access.roles().isEmpty() ? null : access.roles().get(0).code();
        return new DataScope(
                access.userId(),
                role,
                principal == null ? Set.of() : principal.classIds(),
                principal == null ? null : principal.studentId(),
                principal == null ? null : principal.studentNo(),
                access.datasourceIds(),
                access);
    }

    /**
     * Reports whether this scope bypasses table, row and datasource restrictions.
     *
     * @return {@code true} when the resolved access holds the bypass permission
     */
    public boolean bypassesDataScope() {
        return access != null && access.bypassesDataScope();
    }

    /** @return {@code true} when the caller bypasses data-scope filtering */
    public boolean isAdmin() {
        return bypassesDataScope();
    }

    /** @return {@code true} when the caller is a student */
    public boolean isStudent() {
        return Roles.STUDENT.equals(role);
    }

    /** @return {@code true} when the caller is a counselor */
    public boolean isCounselor() {
        return Roles.COUNSELOR.equals(role);
    }
}
