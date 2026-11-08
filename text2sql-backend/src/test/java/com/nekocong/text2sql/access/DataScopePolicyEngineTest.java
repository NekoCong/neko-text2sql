package com.nekocong.text2sql.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DataScopePolicyEngineTest {

    private static final PrincipalAttributes STUDENT =
            new PrincipalAttributes("u-1", "student01", 2L, "2023002", Set.of(1L));

    private final DataScopePolicyEngine engine = new DataScopePolicyEngine();

    @Test
    void deniesATableWithoutAConfiguredPolicy() {
        assertThat(engine.decide(access(Set.of(), List.of()), "campus", "score").denied()).isTrue();
    }

    @Test
    void bypassPermissionMakesEveryTableUnrestricted() {
        PolicyDecision decision =
                engine.decide(access(Set.of(Permissions.BYPASS_DATA_SCOPE), List.of()), "campus", "score");

        assertThat(decision.unrestricted()).isTrue();
    }

    @Test
    void filtersByASingleAttribute() {
        PolicyDecision decision = engine.decide(
                access(Set.of(), List.of(filter("r1", "score", "student_id", "studentId"))),
                "campus",
                "score");

        assertThat(decision.filtered()).isTrue();
        assertThat(decision.predicates())
                .containsExactly(new RowPredicate("student_id", RowPredicate.Operator.EQ, PrincipalAttribute.STUDENT_ID));
    }

    @Test
    void filtersAClassSetWithIn() {
        PolicyDecision decision = engine.decide(
                access(Set.of(), List.of(filter("r1", "class", "id", "classIds"))),
                "campus",
                "class");

        assertThat(decision.predicates())
                .containsExactly(new RowPredicate("id", RowPredicate.Operator.IN, PrincipalAttribute.CLASS_IDS));
    }

    @Test
    void denyWinsOverAll() {
        PolicyDecision decision = engine.decide(
                access(Set.of(), List.of(all("r1", "score"), deny("r2", "score"))),
                "campus",
                "score");

        assertThat(decision.denied()).isTrue();
    }

    @Test
    void allWinsOverFilter() {
        PolicyDecision decision = engine.decide(
                access(Set.of(), List.of(all("r1", "score"), filter("r2", "score", "student_id", "studentId"))),
                "campus",
                "score");

        assertThat(decision.unrestricted()).isTrue();
    }

    @Test
    void orsFiltersAcrossRoles() {
        PolicyDecision decision = engine.decide(
                access(Set.of(), List.of(
                        filter("r1", "score", "student_id", "studentId"),
                        filter("r2", "score", "class_id", "classIds"))),
                "campus",
                "score");

        assertThat(decision.filtered()).isTrue();
        assertThat(decision.predicates()).hasSize(2);
    }

    @Test
    void deniesAnUnknownPrincipalAttribute() {
        PolicyDecision decision = engine.decide(
                access(Set.of(), List.of(filter("r1", "score", "student_id", "unknown"))),
                "campus",
                "score");

        assertThat(decision.denied()).isTrue();
    }

    @Test
    void deniesAnUnsafeColumn() {
        PolicyDecision decision = engine.decide(
                access(Set.of(), List.of(filter("r1", "score", "student_id; DROP TABLE score", "studentId"))),
                "campus",
                "score");

        assertThat(decision.denied()).isTrue();
    }

    private static EffectiveAccess access(Set<String> permissions, List<DataScopeRule> rules) {
        return new EffectiveAccess(
                "u-1",
                List.of(new AccessRole("seed-role-student", "student", "学生", null, null, true, true)),
                permissions,
                Set.of("campus"),
                rules,
                STUDENT);
    }

    private static DataScopeRule filter(String role, String table, String column, String attribute) {
        return new DataScopeRule(
                "scope-" + role + "-" + table,
                role,
                "campus",
                table,
                DataScopePolicy.FILTER,
                column,
                attribute);
    }

    private static DataScopeRule all(String role, String table) {
        return new DataScopeRule(
                "scope-" + role + "-" + table, role, "campus", table, DataScopePolicy.ALL, null, null);
    }

    private static DataScopeRule deny(String role, String table) {
        return new DataScopeRule(
                "scope-" + role + "-" + table, role, "campus", table, DataScopePolicy.DENY, null, null);
    }
}
