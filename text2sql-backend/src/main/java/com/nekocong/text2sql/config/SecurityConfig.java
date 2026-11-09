package com.nekocong.text2sql.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nekocong.text2sql.access.AccessContextResolver;
import com.nekocong.text2sql.access.Permissions;
import com.nekocong.text2sql.security.PermissionAuthorizationManager;
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
import org.springframework.security.authorization.ReactiveAuthorizationManager;
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
import org.springframework.security.web.server.authorization.AuthorizationContext;
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
 * role is available on the {@code Authentication}, but route authorization does not use it: the
 * management routes are gated by the permissions resolved from the access-control tables (see
 * {@link PermissionAuthorizationManager}). Row-level data scoping (who may read which conversation)
 * is enforced in the service layer.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * The security filter chain.
     *
     * <p>The account-status filter is constructed here instead of being exposed as a bean: Spring
     * Boot adds every {@code WebFilter} bean to the top-level handler chain as well, so a bean would
     * validate every request a second time (two JWT decodes and two access resolutions).
     *
     * @param http the server HTTP security builder
     * @param resolver the access-context resolver used by the account-status filter
     * @param jwtDecoder the JWT decoder used to read the bearer token
     * @param objectMapper the shared Jackson mapper used for error bodies
     * @return the filter chain
     */
    @Bean
    SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http,
            AccessContextResolver resolver,
            ReactiveJwtDecoder jwtDecoder,
            ObjectMapper objectMapper) {
        AccessStatusWebFilter accessStatus =
                new AccessStatusWebFilter(resolver, jwtDecoder, objectMapper);
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
                                        // The user lifecycle API is gated by its own permissions
                                        // (user.read for list/detail, user.manage for writes) so an
                                        // operator can manage accounts without editing roles or
                                        // data scopes. These must precede the generic /api/admin/**
                                        // access.manage rule below.
                                        .pathMatchers(
                                                HttpMethod.GET,
                                                "/api/admin/access-control/users",
                                                "/api/admin/access-control/users/*")
                                        .access(userRead())
                                        .pathMatchers(
                                                "/api/admin/access-control/users",
                                                "/api/admin/access-control/users/**")
                                        .access(userManage())
                                        // The audit trail is read-only and gated by audit.read, not
                                        // access.manage, so an auditor can review changes without
                                        // being able to make them. Must precede the generic
                                        // /api/admin/** access.manage rule below.
                                        .pathMatchers(
                                                HttpMethod.GET,
                                                "/api/admin/access-control/audit")
                                        .access(auditRead())
                                        .pathMatchers("/api/admin/**")
                                        .access(accessManage())
                                        // The three knowledge collections, their documents and the
                                        // retrieval debug endpoints require the access-management
                                        // permission.
                                        // The legacy in-memory API stays open to any authenticated
                                        // caller for backwards compatibility.
                                        .pathMatchers("/api/text2sql/knowledge/legacy/**")
                                        .authenticated()
                                        .pathMatchers("/api/text2sql/knowledge/**")
                                        .access(accessManage())
                                        .pathMatchers(
                                                HttpMethod.POST,
                                                "/api/text2sql/datasources",
                                                "/api/text2sql/datasources/**")
                                        .access(datasourceWrite())
                                        .pathMatchers(HttpMethod.PUT, "/api/text2sql/datasources/**")
                                        .access(datasourceWrite())
                                        .pathMatchers(HttpMethod.DELETE, "/api/text2sql/datasources/**")
                                        .access(datasourceWrite())
                                        .anyExchange()
                                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.bearerTokenConverter(bearerTokenConverter)
                                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleAuthenticationConverter())))
                // Re-checks the account (enabled, token version) and publishes the resolved
                // EffectiveAccess on the exchange. It must run before AUTHORIZATION so the
                // permission-based route checks above can read the attribute.
                .addFilterAfter(accessStatus, SecurityWebFiltersOrder.AUTHENTICATION);
        return http.build();
    }

    /**
     * Requires the {@code access.manage} permission (administration of roles and access control).
     *
     * @return the authorization manager
     */
    static ReactiveAuthorizationManager<AuthorizationContext> accessManage() {
        return PermissionAuthorizationManager.permission(Permissions.ACCESS_MANAGE);
    }

    /**
     * Requires the {@code datasource.write} permission.
     *
     * @return the authorization manager
     */
    static ReactiveAuthorizationManager<AuthorizationContext> datasourceWrite() {
        return PermissionAuthorizationManager.permission(Permissions.DATASOURCE_WRITE);
    }

    /**
     * Requires the {@code user.read} permission (list/detail user routes).
     *
     * @return the authorization manager
     */
    static ReactiveAuthorizationManager<AuthorizationContext> userRead() {
        return PermissionAuthorizationManager.permission(Permissions.USER_READ);
    }

    /**
     * Requires the {@code user.manage} permission (user create/status/reset/roles/display-name).
     *
     * @return the authorization manager
     */
    static ReactiveAuthorizationManager<AuthorizationContext> userManage() {
        return PermissionAuthorizationManager.permission(Permissions.USER_MANAGE);
    }

    /**
     * Requires the {@code audit.read} permission (read-only audit-trail access).
     *
     * @return the authorization manager
     */
    static ReactiveAuthorizationManager<AuthorizationContext> auditRead() {
        return PermissionAuthorizationManager.permission(Permissions.AUDIT_READ);
    }

    /**
     * Maps the JWT {@code role} claim to a single granted authority.
     *
     * <p>An unknown or missing role maps to {@code ROLE_NONE}: the caller stays authenticated but
     * carries no role authority. The data-permission layer validates the resolved scope on every
     * query, and the management routes are gated by permissions resolved from the access-control
     * tables, so this authority is informational only.
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
