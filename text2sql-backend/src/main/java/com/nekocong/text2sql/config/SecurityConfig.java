package com.nekocong.text2sql.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nekocong.text2sql.access.AccessContextResolver;
import com.nekocong.text2sql.security.Roles;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Spring Security (WebFlux) configuration: stateless JWT bearer authentication.
 *
 * <p>Public endpoints: registration, login, the liveness probe and the Actuator endpoints.
 * Everything else requires a valid JWT. The token is read from the {@code Authorization: Bearer ...}
 * header, or from an {@code access_token} URI query parameter (needed because the browser
 * {@code EventSource} used for SSE cannot set headers).
 *
 * <p>The {@code role} claim is mapped to a {@code ROLE_ADMIN} / {@code ROLE_USER} authority so the
 * role is available on the {@code Authentication}. Row-level data scoping (who may read which
 * conversation) is enforced in the service layer, which receives the role explicitly.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * The account-status filter.
     *
     * <p>Deliberately declared here rather than as a {@code @Component}: Spring Boot installs every
     * {@code WebFilter} bean on the web handler chain as well as the security chain, so a component
     * would run twice (once before authentication). It is placed explicitly in the security chain
     * below instead.
     *
     * @param resolver the access-context resolver
     * @param jwtDecoder the JWT decoder used to read the bearer token
     * @return the filter
     */
    @Bean
    AccessStatusWebFilter accessStatusWebFilter(
            AccessContextResolver resolver, ReactiveJwtDecoder jwtDecoder) {
        return new AccessStatusWebFilter(resolver, jwtDecoder);
    }

    /**
     * The security filter chain.
     *
     * @param http the server HTTP security builder
     * @param accessStatus the account-status filter, run after bearer authentication
     * @return the filter chain
     */
    @Bean
    SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http, AccessStatusWebFilter accessStatus) {
        ServerBearerTokenAuthenticationConverter bearerTokenConverter =
                new ServerBearerTokenAuthenticationConverter();
        // Allow `?access_token=...` so the SSE endpoint works from the browser's EventSource.
        bearerTokenConverter.setAllowUriQueryParameter(true);

        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(Customizer.withDefaults())
                .authorizeExchange(
                        exchange ->
                                exchange
                                        .pathMatchers(HttpMethod.OPTIONS)
                                        .permitAll()
                                        .pathMatchers(
                                                "/api/auth/register",
                                                "/api/auth/login",
                                                "/api/text2sql/health",
                                                "/actuator/**")
                                        .permitAll()
                                        .pathMatchers("/api/admin/**")
                                        .hasRole("ADMIN")
                                        // The three knowledge collections, their documents and the
                                        // retrieval debug endpoints are administrator-only.
                                        // The legacy in-memory API stays open to any authenticated
                                        // caller for backwards compatibility.
                                        .pathMatchers("/api/text2sql/knowledge/legacy/**")
                                        .authenticated()
                                        .pathMatchers("/api/text2sql/knowledge/**")
                                        .hasRole("ADMIN")
                                        .pathMatchers(
                                                HttpMethod.POST,
                                                "/api/text2sql/datasources",
                                                "/api/text2sql/datasources/**")
                                        .hasRole("ADMIN")
                                        .pathMatchers(HttpMethod.PUT, "/api/text2sql/datasources/**")
                                        .hasRole("ADMIN")
                                        .pathMatchers(HttpMethod.DELETE, "/api/text2sql/datasources/**")
                                        .hasRole("ADMIN")
                                        .anyExchange()
                                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.bearerTokenConverter(bearerTokenConverter)
                                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleAuthenticationConverter())))
                // Re-checks the account (enabled, token version) and publishes the resolved
                // EffectiveAccess on the exchange before the controller runs.
                .addFilterAfter(accessStatus, SecurityWebFiltersOrder.AUTHORIZATION);
        return http.build();
    }

    /**
     * Maps the JWT {@code role} claim to a single granted authority.
     *
     * <p>An unknown or missing role maps to {@code ROLE_NONE}: the caller stays authenticated but
     * carries no role authority. The data-permission layer validates the resolved scope on every
     * query, and {@code /api/admin/**} plus datasource mutation endpoints require {@code ROLE_ADMIN}.
     *
     * @return the converter
     */
    @Bean
    Converter<Jwt, Mono<AbstractAuthenticationToken>> roleAuthenticationConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> {
                    String role = Roles.normalize(jwt.getClaimAsString(Roles.CLAIM));
                    String authority = "ROLE_" + (role == null ? "NONE" : role.toUpperCase(Locale.ROOT));
                    return Flux.just(new SimpleGrantedAuthority(authority));
                });
        return converter;
    }

    /**
     * The password encoder.
     *
     * @return a BCrypt encoder
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The HMAC key used to sign and verify JWTs.
     *
     * @param properties the security configuration
     * @return the secret key
     */
    @Bean
    SecretKey jwtSecretKey(SecurityProperties properties) {
        return new SecretKeySpec(
                properties.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * The JWT encoder.
     *
     * @param key the signing key
     * @return the encoder
     */
    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /**
     * The JWT decoder used by the resource server.
     *
     * @param key the verification key
     * @return the decoder
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(SecretKey key) {
        return NimbusReactiveJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
