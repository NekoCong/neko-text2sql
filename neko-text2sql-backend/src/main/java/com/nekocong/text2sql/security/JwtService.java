package com.nekocong.text2sql.security;

import com.nekocong.text2sql.config.SecurityProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues signed JWTs for authenticated users.
 *
 * <p>The token subject is the user id. The {@code role} claim is display-only: authorization is
 * resolved from the access-control tables on every request, so a role change applies to the next
 * request. The {@code ver} claim is the session version of the account; a token whose version does
 * not match {@code app_user.token_version} is rejected, which invalidates every token issued before
 * an account is disabled, re-enabled or has its password changed.
 */
@Component
public class JwtService {

    private final JwtEncoder encoder;
    private final SecurityProperties properties;

    /**
     * Creates the service.
     *
     * @param encoder the JWT encoder
     * @param properties the security configuration
     */
    public JwtService(JwtEncoder encoder, SecurityProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /**
     * Issues an access token for a user.
     *
     * @param userId the user id (token subject)
     * @param username the username claim
     * @param role the role claim ({@code admin}, {@code student} or {@code counselor})
     * @param tokenVersion the session version of the account ({@code ver} claim)
     * @return the compact JWT
     */
    public String issue(String userId, String username, String role, int tokenVersion) {
        Instant now = Instant.now();
        String canonical = Roles.normalize(role);
        // Legacy or unrecognized roles are preserved verbatim so the token stays issuable; the
        // permission layer rejects them because normalize() no longer recognizes them.
        String claimRole = canonical != null ? canonical : (role == null ? "" : role);
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(properties.getIssuer())
                        .subject(userId)
                        .claim("username", username)
                        .claim(Roles.CLAIM, claimRole)
                        .claim("ver", tokenVersion)
                        .issuedAt(now)
                        .expiresAt(now.plus(properties.getTokenTtlMinutes(), ChronoUnit.MINUTES))
                        .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * Returns the access-token lifetime in seconds.
     *
     * @return the TTL in seconds
     */
    public long ttlSeconds() {
        return properties.getTokenTtlMinutes() * 60;
    }
}
