package com.nekocong.text2sql.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.access.AccountStatus;
import com.nekocong.text2sql.access.AccessRole;
import com.nekocong.text2sql.access.DataScopeRule;
import com.nekocong.text2sql.access.PrincipalAttributes;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.security.JwtService;
import com.nekocong.text2sql.user.AppUser;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Unit tests for the sign-in and registration rules of {@link AuthService}, with the persistence
 * layer stubbed so no database is needed.
 */
class AuthServiceTest {

    private static final AppUser DISABLED =
            new AppUser("u-disabled", "blocked", "hash", "Blocked", "student", false, 0);
    private static final AppUser ENABLED =
            new AppUser("u-enabled", "student01", "hash", "Student", "student", true, 3);

    @Test
    void rejectsADisabledAccountEvenWithTheCorrectPassword() {
        AuthService service = new AuthService(new FakeUsers(DISABLED), new FakeAccess(), encoder(true), jwt("tok"));

        assertThatThrownBy(() -> service.login("blocked", "secret123").block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException failure = (ResponseStatusException) error;
                    assertThat(failure.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
                    assertThat(failure.getReason()).isEqualTo("账号已停用，请联系管理员");
                });
    }

    @Test
    void issuesATokenCarryingTheAccountTokenVersion() {
        AuthService service = new AuthService(new FakeUsers(ENABLED), new FakeAccess(), encoder(true), jwt("tok"));

        AuthResult result = service.login("student01", "secret123").block();

        assertThat(result).isNotNull();
        assertThat(result.token()).isEqualTo("tok");
    }

    @Test
    void registersAStudentAndAssignsTheBuiltInRole() {
        FakeAccess access = new FakeAccess();
        AuthService service = new AuthService(new FakeUsers(null), access, encoder(false), jwt("tok"));

        AuthResult result = service.register("newbie", "secret123", null).block();

        assertThat(result).isNotNull();
        assertThat(access.assignedRole).isEqualTo("student");
    }

    @Test
    void failsRegistrationWhenTheBuiltInRoleIsMissing() {
        FakeAccess access = new FakeAccess();
        access.failAssignment = true;
        AuthService service = new AuthService(new FakeUsers(null), access, encoder(false), jwt("tok"));

        assertThatThrownBy(() -> service.register("newbie", "secret123", null).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

    private static PasswordEncoder encoder(boolean matches) {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return "hash";
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return matches;
            }
        };
    }

    private static JwtService jwt(String token) {
        return new JwtService(null, null) {
            @Override
            public String issue(String userId, String username, String role, int tokenVersion) {
                return token;
            }

            @Override
            public long ttlSeconds() {
                return 3600;
            }
        };
    }

    private static final class FakeUsers extends UserRepository {

        private final AppUser user;

        FakeUsers(AppUser user) {
            super(null);
            this.user = user;
        }

        @Override
        public Mono<AppUser> findByUsername(String username) {
            return Mono.justOrEmpty(user);
        }

        @Override
        public Mono<AppUser> insert(String username, String passwordHash, String displayName, String role) {
            return Mono.just(new AppUser("u-new", username, passwordHash, displayName, role, true, 0));
        }
    }

    private static final class FakeAccess implements AccessControlRepository {

        private String assignedRole;
        private boolean failAssignment;

        @Override
        public Mono<Void> assignRoleByCode(String userId, String roleCode) {
            if (failAssignment) {
                return Mono.error(new IllegalStateException("角色不存在：" + roleCode));
            }
            assignedRole = roleCode;
            return Mono.empty();
        }

        @Override
        public Mono<AccountStatus> account(String userId) {
            return Mono.empty();
        }

        @Override
        public Mono<List<AccessRole>> rolesOf(String userId) {
            return Mono.just(List.of());
        }

        @Override
        public Mono<List<AccessRole>> allRoles() {
            return Mono.just(List.of());
        }

        @Override
        public Mono<Set<String>> permissionKeys(Collection<String> roleIds) {
            return Mono.just(Set.of());
        }

        @Override
        public Mono<Set<String>> datasourceIds(Collection<String> roleIds) {
            return Mono.just(Set.of());
        }

        @Override
        public Mono<List<DataScopeRule>> dataScopes(Collection<String> roleIds) {
            return Mono.just(List.of());
        }

        @Override
        public Mono<PrincipalAttributes> principal(String userId, String username) {
            return Mono.just(new PrincipalAttributes(userId, username, null, null, Set.of()));
        }
    }
}
