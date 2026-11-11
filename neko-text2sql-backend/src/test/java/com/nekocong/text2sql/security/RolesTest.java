package com.nekocong.text2sql.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Role vocabulary and normalization rules.
 *
 * <p>The important property is fail-closed normalization: an unknown or missing role must not be
 * silently mapped onto a scoped role, because that would hand out a scope the account was never
 * configured for.
 */
class RolesTest {

    @Test
    void recognizesTheThreeCanonicalRoles() {
        assertThat(Roles.normalize("admin")).isEqualTo(Roles.ADMIN);
        assertThat(Roles.normalize("ADMIN")).isEqualTo(Roles.ADMIN);
        assertThat(Roles.normalize("student")).isEqualTo(Roles.STUDENT);
        assertThat(Roles.normalize("Counselor")).isEqualTo(Roles.COUNSELOR);
    }

    @Test
    void unknownAndLegacyRolesAreRejected() {
        assertThat(Roles.normalize("user")).isNull();
        assertThat(Roles.normalize("teacher")).isNull();
        assertThat(Roles.normalize("")).isNull();
        assertThat(Roles.normalize(null)).isNull();
    }

    @Test
    void onlyTheAdminRoleIsRecognizedAsAdmin() {
        assertThat(Roles.isAdmin(jwt("admin"))).isTrue();
        assertThat(Roles.isAdmin(jwt("student"))).isFalse();
        assertThat(Roles.isAdmin(jwt("counselor"))).isFalse();
        assertThat(Roles.isAdmin(jwt("user"))).isFalse();
        assertThat(Roles.isAdmin(null)).isFalse();
    }

    private static Jwt jwt(String role) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject("u1")
                .claim(Roles.CLAIM, role)
                .build();
    }
}
