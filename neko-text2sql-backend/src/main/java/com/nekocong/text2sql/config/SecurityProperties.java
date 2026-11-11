package com.nekocong.text2sql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security configuration, bound from {@code text2sql.security.*}.
 */
@ConfigurationProperties(prefix = "text2sql.security")
public class SecurityProperties {

    /**
     * HMAC secret used to sign/verify JWTs. MUST be at least 32 bytes for HS256. Override in
     * production via the {@code TEXT2SQL_JWT_SECRET} environment variable.
     */
    private String jwtSecret = "neko-text2sql-dev-jwt-secret-change-me-please-32b+";

    /** Access-token lifetime, in minutes. */
    private long tokenTtlMinutes = 720;

    /** Token issuer claim. */
    private String issuer = "text2sql";

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getTokenTtlMinutes() {
        return tokenTtlMinutes;
    }

    public void setTokenTtlMinutes(long tokenTtlMinutes) {
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
