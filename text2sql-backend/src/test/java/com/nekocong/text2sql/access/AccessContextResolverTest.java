package com.nekocong.text2sql.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class AccessContextResolverTest {

    private static final PrincipalAttributes PRINCIPAL =
            new PrincipalAttributes("u-1", "student01", 2L, "2023002", Set.of(1L));

    @Test
    void rejectsADisabledAccount() {
        FakeRepository repository = new FakeRepository();
        repository.account = new AccountStatus("u-1", "student01", "student", false, 0);

        assertStatus(new AccessContextResolver(repository).resolve("u-1", 0), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAStaleTokenVersion() {
        FakeRepository repository = new FakeRepository();
        repository.account = new AccountStatus("u-1", "student01", "student", true, 2);

        assertStatus(new AccessContextResolver(repository).resolve("u-1", 1), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAnAccountWithoutRoles() {
        FakeRepository repository = new FakeRepository();

        assertStatus(new AccessContextResolver(repository).resolve("u-1", 0), HttpStatus.FORBIDDEN);
    }

    @Test
    void unionsInheritedRolesPermissionsGrantsAndScopes() {
        FakeRepository repository = new FakeRepository();
        AccessRole child = role("r-child", "assistant", "r-parent", true);
        AccessRole parent = role("r-parent", "student", null, true);
        repository.all = List.of(child, parent);
        repository.direct = List.of(child);
        repository.permissions = Set.of("chat.use", "datasource.read");
        repository.datasources = Set.of("campus");
        repository.scopes = List.of(new DataScopeRule(
                "s1", "r-parent", "campus", "score", DataScopePolicy.FILTER, "student_id", "studentId"));

        EffectiveAccess access = new AccessContextResolver(repository).resolve("u-1", 0).block();

        assertThat(access).isNotNull();
        assertThat(access.roleCodes()).containsExactlyInAnyOrder("assistant", "student");
        assertThat(access.permissions()).containsExactlyInAnyOrder("chat.use", "datasource.read");
        assertThat(access.datasourceIds()).containsExactly("campus");
        assertThat(access.dataScopes()).hasSize(1);
        assertThat(access.principal()).isEqualTo(PRINCIPAL);
        assertThat(access.bypassesDataScope()).isFalse();
    }

    @Test
    void excludesADisabledParent() {
        FakeRepository repository = new FakeRepository();
        AccessRole child = role("r-child", "assistant", "r-parent", true);
        AccessRole parent = role("r-parent", "student", null, false);
        repository.all = List.of(child, parent);
        repository.direct = List.of(child);
        repository.permissions = Set.of("chat.use");

        EffectiveAccess access = new AccessContextResolver(repository).resolve("u-1", 0).block();

        assertThat(access).isNotNull();
        assertThat(access.roleCodes()).containsExactly("assistant");
    }

    @Test
    void rejectsAnInheritanceCycle() {
        FakeRepository repository = new FakeRepository();
        AccessRole a = role("a", "a", "b", true);
        AccessRole b = role("b", "b", "a", true);
        repository.all = List.of(a, b);
        repository.direct = List.of(a);

        assertStatus(new AccessContextResolver(repository).resolve("u-1", 0), HttpStatus.FORBIDDEN);
    }

    private static void assertStatus(Mono<EffectiveAccess> result, HttpStatus expected) {
        assertThatThrownBy(result::block)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(expected.value()));
    }

    private static AccessRole role(String id, String code, String parentId, boolean enabled) {
        return new AccessRole(id, code, code, null, parentId, false, enabled);
    }

    private static final class FakeRepository implements AccessControlRepository {

        private AccountStatus account = new AccountStatus("u-1", "student01", "student", true, 0);
        private List<AccessRole> direct = List.of();
        private List<AccessRole> all = List.of();
        private Set<String> permissions = Set.of();
        private Set<String> datasources = Set.of();
        private List<DataScopeRule> scopes = List.of();

        @Override
        public Mono<AccountStatus> account(String userId) {
            return Mono.justOrEmpty(account);
        }

        @Override
        public Mono<List<AccessRole>> rolesOf(String userId) {
            return Mono.just(direct);
        }

        @Override
        public Mono<List<AccessRole>> allRoles() {
            return Mono.just(all);
        }

        @Override
        public Mono<Set<String>> permissionKeys(Collection<String> roleIds) {
            return Mono.just(permissions);
        }

        @Override
        public Mono<Set<String>> datasourceIds(Collection<String> roleIds) {
            return Mono.just(datasources);
        }

        @Override
        public Mono<List<DataScopeRule>> dataScopes(Collection<String> roleIds) {
            return Mono.just(scopes);
        }

        @Override
        public Mono<PrincipalAttributes> principal(String userId, String username) {
            return Mono.just(PRINCIPAL);
        }
    }
}
