package com.nekocong.text2sql.security;

import com.nekocong.text2sql.access.AccessRole;
import com.nekocong.text2sql.access.DataScopePolicy;
import com.nekocong.text2sql.access.DataScopeRule;
import com.nekocong.text2sql.access.EffectiveAccess;
import com.nekocong.text2sql.access.Permissions;
import com.nekocong.text2sql.access.PrincipalAttributes;
import java.util.List;
import java.util.Set;

/**
 * Test-only fixtures: effective access assembled from the same seeded policies as
 * {@code db/app-data.sql}, without touching a database.
 */
public final class AccessFixtures {

    public static final String CAMPUS = "campus";

    private AccessFixtures() {}

    public static DataScope student() {
        return DataScope.from(builder(Roles.STUDENT, Set.of(CAMPUS), List.of(
                        rule("s-class", CAMPUS, "class", DataScopePolicy.FILTER, "id", "classIds"),
                        rule("s-student", CAMPUS, "student", DataScopePolicy.FILTER, "student_no", "studentNo"),
                        rule("s-score", CAMPUS, "score", DataScopePolicy.FILTER, "student_id", "studentId"),
                        rule("s-attendance", CAMPUS, "attendance", DataScopePolicy.FILTER, "student_id", "studentId"),
                        rule("s-course", CAMPUS, "course", DataScopePolicy.ALL, null, null)))
                .principal(new PrincipalAttributes("u-student", "student01", 1L, "2023001", Set.of(1L)))
                .build());
    }

    public static DataScope counselor() {
        return DataScope.from(builder(Roles.COUNSELOR, Set.of(CAMPUS), List.of(
                        rule("c-class", CAMPUS, "class", DataScopePolicy.FILTER, "id", "classIds"),
                        rule("c-student", CAMPUS, "student", DataScopePolicy.FILTER, "class_id", "classIds"),
                        rule("c-score", CAMPUS, "score", DataScopePolicy.FILTER, "class_id", "classIds"),
                        rule("c-attendance", CAMPUS, "attendance", DataScopePolicy.FILTER, "class_id", "classIds"),
                        rule("c-course", CAMPUS, "course", DataScopePolicy.ALL, null, null)))
                .principal(new PrincipalAttributes("u-counselor", "counselor01", null, null, Set.of(1L, 2L)))
                .build());
    }

    public static DataScope admin() {
        return DataScope.from(builder(Roles.ADMIN, Set.of(), List.of())
                .permissions(Set.of(Permissions.BYPASS_DATA_SCOPE))
                .principal(new PrincipalAttributes("u-admin", "admin", null, null, Set.of()))
                .build());
    }

    /**
     * The student policies, but without any datasource grant: nothing is visible in any datasource.
     *
     * @return the scope
     */
    public static DataScope studentWithoutCampusGrant() {
        EffectiveAccess base = student().access();
        return DataScope.from(new EffectiveAccess(
                base.userId(),
                base.roles(),
                base.permissions(),
                Set.of(),
                base.dataScopes(),
                base.principal()));
    }

    /**
     * A role whose code is not one of the seeded names, so any pass can only come from its policy.
     *
     * @param roleCode the custom role code
     * @param rules the configured policies
     * @param classIds the classes in scope
     * @return the scope
     */
    public static DataScope custom(
            String roleCode, List<DataScopeRule> rules, Set<Long> classIds) {
        return custom(roleCode, rules, classIds, null, null);
    }

    /**
     * A custom-role scope with extra principal inputs.
     *
     * @param roleCode the custom role code
     * @param rules the configured policies
     * @param classIds the classes in scope
     * @param studentNo the student number, or {@code null}
     * @param studentId the student id, or {@code null}
     * @return the scope
     */
    public static DataScope custom(
            String roleCode,
            List<DataScopeRule> rules,
            Set<Long> classIds,
            String studentNo,
            Long studentId) {
        return DataScope.from(builder(roleCode, Set.of(CAMPUS), rules)
                .principal(new PrincipalAttributes(
                        "u-" + roleCode, roleCode, studentId, studentNo, classIds))
                .build());
    }

    public static DataScopeRule rule(
            String id,
            String datasourceId,
            String table,
            DataScopePolicy policy,
            String column,
            String attribute) {
        return new DataScopeRule(id, "r", datasourceId, table, policy, column, attribute);
    }

    public static Builder builder(String roleCode, Set<String> datasources, List<DataScopeRule> rules) {
        return new Builder(roleCode, datasources, rules);
    }

    /** Small builder so fixtures state only the parts they care about. */
    public static final class Builder {
        private final String roleCode;
        private final Set<String> datasources;
        private final List<DataScopeRule> rules;
        private Set<String> permissions = Set.of();
        private PrincipalAttributes principal = new PrincipalAttributes("u-1", "user01", null, null, Set.of());

        private Builder(String roleCode, Set<String> datasources, List<DataScopeRule> rules) {
            this.roleCode = roleCode;
            this.datasources = datasources;
            this.rules = rules;
        }

        public Builder permissions(Set<String> permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder principal(PrincipalAttributes principal) {
            this.principal = principal;
            return this;
        }

        public EffectiveAccess build() {
            AccessRole role = new AccessRole("r", roleCode, roleCode, null, null, false, true);
            return new EffectiveAccess(
                    principal.userId(), List.of(role), permissions, datasources, rules, principal);
        }
    }
}
