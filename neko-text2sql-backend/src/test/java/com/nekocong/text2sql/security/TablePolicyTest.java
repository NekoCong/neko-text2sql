package com.nekocong.text2sql.security;

import static com.nekocong.text2sql.security.AccessFixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nekocong.text2sql.access.DataScopePolicy;
import com.nekocong.text2sql.access.EffectiveAccess;
import com.nekocong.text2sql.access.PrincipalAttributes;
import com.nekocong.text2sql.access.PrincipalAttribute;
import com.nekocong.text2sql.access.RowPredicate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The permission matrix, now data-driven: table visibility and row predicates come from the
 * configured {@code access_data_scope} rules, never from the role name. An undeclared table is
 * denied, a scope without resolved access is denied, and a custom role is enforced the same way as
 * a seeded one.
 */
class TablePolicyTest {

    private static final String CAMPUS = AccessFixtures.CAMPUS;

    private final TablePolicy policy = new TablePolicy();

    @Test
    void seededRolesSeeExactlyTheirConfiguredTables() {
        for (DataScope scope : List.of(AccessFixtures.student(), AccessFixtures.counselor())) {
            for (String table : List.of("class", "course", "student", "score", "attendance")) {
                assertThat(policy.allows(scope, CAMPUS, table))
                        .as("%s may read %s", scope.role(), table)
                        .isTrue();
            }
            assertThat(policy.allows(scope, CAMPUS, "salary")).isFalse();
            assertThatThrownBy(() -> policy.requireAllowed(scope, CAMPUS, "salary"))
                    .isInstanceOf(SqlPermissionException.class)
                    .hasMessageContaining("salary");
        }
    }

    @Test
    void bypassRoleIsNotRestrictedByTheWhitelist() {
        DataScope admin = AccessFixtures.admin();

        assertThatCode(() -> policy.requireAllowed(admin, CAMPUS, "salary")).doesNotThrowAnyException();
        assertThat(policy.visibleTables(admin, CAMPUS)).isNull();
        assertThat(policy.predicates(admin, CAMPUS, "student")).isEmpty();
    }

    @Test
    void aScopeWithoutResolvedAccessIsDenied() {
        // The legacy 6-arg constructor carries no access: every decision fails closed.
        DataScope legacy = new DataScope("u", "student", Set.of(1L), 1L, "2023001", Set.of(CAMPUS));

        assertThat(policy.allows(legacy, CAMPUS, "student")).isFalse();
        assertThat(policy.visibleTables(legacy, CAMPUS)).isEmpty();
        assertThatThrownBy(() -> policy.requireAllowed(legacy, CAMPUS, "student"))
                .isInstanceOf(SqlPermissionException.class);
        assertThatThrownBy(() -> policy.predicates(legacy, CAMPUS, "student"))
                .isInstanceOf(SqlPermissionException.class);
    }

    @Test
    void aScopeWithNoRulesSeesNoTables() {
        DataScope bare = DataScope.from(AccessFixtures.builder("auditor", Set.of(CAMPUS), List.of()).build());

        assertThat(policy.visibleTables(bare, CAMPUS)).isEmpty();
        assertThat(policy.allows(bare, CAMPUS, "student")).isFalse();
        assertThatThrownBy(() -> policy.requireAllowed(bare, CAMPUS, "student"))
                .isInstanceOf(SqlPermissionException.class);
    }

    @Test
    void aRuleForAnotherDatasourceGrantsNothing() {
        DataScope scope = AccessFixtures.custom(
                "auditor",
                List.of(rule("a1", "warehouse", "student", DataScopePolicy.ALL, null, null)),
                Set.of());

        assertThat(policy.allows(scope, CAMPUS, "student")).isFalse();
        assertThat(policy.visibleTables(scope, CAMPUS)).isEmpty();
    }

    @Test
    void studentIsFilteredByItsOwnIdentity() {
        DataScope student = AccessFixtures.student();

        assertThat(policy.predicates(student, CAMPUS, "student"))
                .containsExactly(
                        new RowPredicate("student_no", RowPredicate.Operator.EQ, PrincipalAttribute.STUDENT_NO));
        assertThat(policy.predicates(student, CAMPUS, "score"))
                .containsExactly(
                        new RowPredicate("student_id", RowPredicate.Operator.EQ, PrincipalAttribute.STUDENT_ID));
        assertThat(policy.predicates(student, CAMPUS, "attendance"))
                .containsExactly(
                        new RowPredicate("student_id", RowPredicate.Operator.EQ, PrincipalAttribute.STUDENT_ID));
        assertThat(policy.predicates(student, CAMPUS, "class"))
                .containsExactly(
                        new RowPredicate("id", RowPredicate.Operator.IN, PrincipalAttribute.CLASS_IDS));
        assertThat(policy.predicates(student, CAMPUS, "course")).isEmpty();
    }

    @Test
    void counselorIsFilteredByManagedClasses() {
        DataScope counselor = AccessFixtures.counselor();

        for (String table : List.of("student", "score", "attendance")) {
            assertThat(policy.predicates(counselor, CAMPUS, table))
                    .containsExactly(
                            new RowPredicate("class_id", RowPredicate.Operator.IN, PrincipalAttribute.CLASS_IDS));
        }
        assertThat(policy.predicates(counselor, CAMPUS, "class"))
                .containsExactly(
                        new RowPredicate("id", RowPredicate.Operator.IN, PrincipalAttribute.CLASS_IDS));
        assertThat(policy.predicates(counselor, CAMPUS, "course")).isEmpty();
    }

    @Test
    void visibleTablesListsEveryNonDeniedConfiguredTable() {
        assertThat(policy.visibleTables(AccessFixtures.student(), CAMPUS))
                .containsExactlyInAnyOrder("class", "course", "student", "score", "attendance");
        assertThat(policy.visibleTables(AccessFixtures.student(), "warehouse")).isEmpty();
    }

    @Test
    void aCustomRoleNameIsEnforcedFromItsPolicy() {
        // No "student"/"counselor" in the code: enforcement must follow the DB rule.
        DataScope scope = AccessFixtures.custom(
                "teacher",
                List.of(
                        rule("t1", CAMPUS, "score", DataScopePolicy.FILTER, "class_id", "classIds"),
                        rule("t2", CAMPUS, "course", DataScopePolicy.ALL, null, null)),
                Set.of(4L));

        assertThat(policy.visibleTables(scope, CAMPUS)).containsExactlyInAnyOrder("score", "course");
        assertThat(policy.predicates(scope, CAMPUS, "score"))
                .containsExactly(
                        new RowPredicate("class_id", RowPredicate.Operator.IN, PrincipalAttribute.CLASS_IDS));
        assertThat(policy.allows(scope, CAMPUS, "student")).isFalse();
    }

    @Test
    void aDenyRuleWinsOverAnyGrant() {
        DataScope scope = AccessFixtures.custom(
                "mixed",
                List.of(
                        rule("m1", CAMPUS, "score", DataScopePolicy.ALL, null, null),
                        rule("m2", CAMPUS, "score", DataScopePolicy.DENY, null, null)),
                Set.of());

        assertThat(policy.allows(scope, CAMPUS, "score")).isFalse();
    }

    @Test
    void unsafeIdentifiersAreDenied() {
        DataScope injector = AccessFixtures.custom(
                "injector",
                List.of(rule("i1", CAMPUS, "score", DataScopePolicy.FILTER, "student_id; DROP TABLE student", "studentId")),
                Set.of());

        assertThat(policy.allows(injector, CAMPUS, "score")).isFalse();
        assertThat(policy.allows(AccessFixtures.student(), CAMPUS, "score; DROP TABLE student")).isFalse();
    }

    @Test
    void dataScopeFromProjectsTheEffectiveAccess() {
        EffectiveAccess access = AccessFixtures.builder(Roles.STUDENT, Set.of(CAMPUS), List.of())
                .principal(new PrincipalAttributes("u-1", "n1", 9L, "2023009", Set.of(3L)))
                .build();

        DataScope scope = DataScope.from(access);

        assertThat(scope.userId()).isEqualTo("u-1");
        assertThat(scope.role()).isEqualTo(Roles.STUDENT);
        assertThat(scope.classIds()).containsExactly(3L);
        assertThat(scope.studentId()).isEqualTo(9L);
        assertThat(scope.studentNo()).isEqualTo("2023009");
        assertThat(scope.allowedDatasources()).containsExactly(CAMPUS);
        assertThat(scope.access()).isSameAs(access);
        assertThat(scope.bypassesDataScope()).isFalse();
        assertThat(DataScope.from(null).access()).isNull();
        assertThat(DataScope.from(null).bypassesDataScope()).isFalse();
    }
}
