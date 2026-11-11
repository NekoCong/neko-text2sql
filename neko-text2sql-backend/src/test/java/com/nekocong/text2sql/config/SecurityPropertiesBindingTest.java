package com.nekocong.text2sql.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Guards the {@code TEXT2SQL_JWT_SECRET} environment variable actually reaching
 * {@link SecurityProperties#getJwtSecret()}.
 *
 * <p>{@code jwtSecret} lives under the {@code text2sql.security} prefix, so Spring's relaxed
 * binding alone would only look for {@code TEXT2SQL_SECURITY_JWTSECRET}. The placeholder in
 * {@code application.yml} is what bridges the documented name. Without this test the failure mode
 * is silent: a shared deployment would keep signing tokens with the built-in demo key, which is
 * public in this repository, so anyone could forge an admin token.
 */
@SpringBootTest(properties = "TEXT2SQL_JWT_SECRET=env-provided-secret-that-is-at-least-32-bytes")
class SecurityPropertiesBindingTest {

    @Autowired
    private SecurityProperties properties;

    @Test
    void jwtSecretComesFromTheEnvironmentVariable() {
        assertThat(properties.getJwtSecret())
                .isEqualTo("env-provided-secret-that-is-at-least-32-bytes")
                .doesNotContain("change-me");
    }
}
