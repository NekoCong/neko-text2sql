package com.nekocong.text2sql.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.access.AccessContextResolver;
import com.nekocong.text2sql.access.EffectiveAccess;
import com.nekocong.text2sql.security.TokenClaims;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Validates the account behind every authenticated request and publishes the resolved access.
 *
 * <p>A valid JWT only proves identity; the account may have been disabled or its session version
 * bumped since the token was issued. This filter re-checks the account through {@link
 * AccessContextResolver} and stores the {@link EffectiveAccess} on the exchange attributes, where
 * enforcement code looks it up with {@code EffectiveAccess.class.getName()}.
 *
 * <p>The bearer token is read straight from the request (the {@code Authorization} header, or the
 * {@code access_token} query parameter used by the browser {@code EventSource}) and verified with
 * the same decoder as the resource server. The {@code ver} claim is read with {@link TokenClaims}.
 * The exchange principal is deliberately not touched: in WebFlux it is backed by a single shared
 * security-context publisher, and consuming it here would starve the downstream filters and
 * controller argument resolution.
 *
 * <p>The filter only validates before the chain runs: it never reads or buffers the response body,
 * so server-sent events stream through unchanged. Requests without a bearer token (public
 * endpoints) pass through untouched.
 *
 * <p>It is constructed by {@link SecurityConfig} and registered only in the security chain. It must
 * not be exposed as a bean: Spring Boot adds every {@code WebFilter} bean to the top-level handler
 * chain as well, which would validate every request a second time.
 */
public class AccessStatusWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessStatusWebFilter.class);

    /** Exchange attribute under which the resolved access is published. */
    private static final String ACCESS = EffectiveAccess.class.getName();

    /** Query parameter accepted as a bearer token for the browser {@code EventSource}. */
    private static final String TOKEN_PARAM = "access_token";

    /** The message used when validation fails for a reason other than an account rule. */
    private static final String DEFAULT_MESSAGE = "登录状态已失效，请重新登录";

    /** The message used for unexpected server-side failures, which must not leak internals. */
    private static final String SERVER_ERROR_MESSAGE = "服务器内部错误，请稍后重试";

    private static final String BEARER_PREFIX = "Bearer ";

    private final AccessContextResolver resolver;
    private final ReactiveJwtDecoder decoder;
    private final ObjectMapper objectMapper;

    /**
     * Creates the filter.
     *
     * @param resolver the access-context resolver
     * @param decoder the JWT decoder, shared with the resource server
     * @param objectMapper the shared Jackson mapper, used for the error body
     */
    public AccessStatusWebFilter(
            AccessContextResolver resolver, ReactiveJwtDecoder decoder, ObjectMapper objectMapper) {
        this.resolver = resolver;
        this.decoder = decoder;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = token(exchange);
        if (token == null) {
            return chain.filter(exchange);
        }
        return decoder.decode(token)
                .flatMap(jwt -> resolve(exchange, jwt).then(Mono.defer(() -> chain.filter(exchange))))
                // An invalid or expired token is left to the resource server so the normal
                // authentication entry point answers; only account-level failures are handled here.
                .onErrorResume(JwtException.class, ex -> chain.filter(exchange));
    }

    /** Reads the bearer token from the request, or {@code null} when absent. */
    private static String token(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String value = header.substring(BEARER_PREFIX.length()).trim();
            return value.isEmpty() ? null : value;
        }
        String parameter = exchange.getRequest().getQueryParams().getFirst(TOKEN_PARAM);
        return parameter == null || parameter.isBlank() ? null : parameter.trim();
    }

    /**
     * Resolves the account and publishes the access.
     *
     * <p>The error handling only wraps the resolution: anything raised further down the chain (an
     * authorization {@code 403}, a controller error, a broken SSE stream) propagates untouched.
     */
    private Mono<Void> resolve(ServerWebExchange exchange, Jwt jwt) {
        return resolver.resolve(jwt.getSubject(), TokenClaims.tokenVersion(jwt))
                .doOnNext(access -> exchange.getAttributes().put(ACCESS, access))
                .then()
                .onErrorResume(error -> {
                    if (exchange.getResponse().isCommitted()) {
                        return Mono.error(error);
                    }
                    return reject(exchange, jwt.getSubject(), error);
                });
    }

    /**
     * Writes the failure status with a compact JSON body and completes the response.
     *
     * <p>Only a deliberate {@link ResponseStatusException} reaches the caller verbatim (its status
     * and reason are safe to expose). Any other failure is an infrastructure problem: it is logged
     * with its cause and answered with a generic {@code 500}, never as a misleading "session expired"
     * {@code 401}.
     */
    private Mono<Void> reject(ServerWebExchange exchange, String userId, Throwable error) {
        HttpStatus status;
        String message;
        if (error instanceof ResponseStatusException failure) {
            HttpStatus resolved = HttpStatus.resolve(failure.getStatusCode().value());
            status = resolved == null ? HttpStatus.UNAUTHORIZED : resolved;
            String reason = failure.getReason();
            message = reason == null || reason.isBlank() ? DEFAULT_MESSAGE : reason;
        } else {
            log.error("Failed to resolve access for user {}", userId, error);
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = SERVER_ERROR_MESSAGE;
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = body(message);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /** Serializes {@code {"message": ...}}; falls back to a fixed body if serialization fails. */
    private byte[] body(String message) {
        try {
            return objectMapper.writeValueAsBytes(Map.of("message", message));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize the access-rejection body", ex);
            return ("{\"message\":\"" + DEFAULT_MESSAGE + "\"}").getBytes(StandardCharsets.UTF_8);
        }
    }
}
