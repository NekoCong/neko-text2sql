package com.nekocong.text2sql.config;

import com.nekocong.text2sql.access.AccessContextResolver;
import com.nekocong.text2sql.access.EffectiveAccess;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
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
 * the same decoder as the resource server. The exchange principal is deliberately not touched: in
 * WebFlux it is backed by a single shared security-context publisher, and consuming it here would
 * starve the downstream filters and controller argument resolution.
 *
 * <p>The filter only validates before the chain runs: it never reads or buffers the response body,
 * so server-sent events stream through unchanged. Requests without a bearer token (public
 * endpoints) pass through untouched.
 *
 * <p>It is registered explicitly by {@link SecurityConfig} rather than as a component bean: Spring
 * Boot also installs every {@code WebFilter} bean on the web handler chain, which would run it a
 * second time, before authentication.
 */
public class AccessStatusWebFilter implements WebFilter {

    /** Exchange attribute under which the resolved access is published. */
    private static final String ACCESS = EffectiveAccess.class.getName();

    /** Query parameter accepted as a bearer token for the browser {@code EventSource}. */
    private static final String TOKEN_PARAM = "access_token";

    /** The message used when validation fails for a reason other than an account rule. */
    private static final String DEFAULT_MESSAGE = "登录状态已失效，请重新登录";

    private static final String BEARER_PREFIX = "Bearer ";

    private final AccessContextResolver resolver;
    private final ReactiveJwtDecoder decoder;

    /**
     * Creates the filter.
     *
     * @param resolver the access-context resolver
     * @param decoder the JWT decoder, shared with the resource server
     */
    public AccessStatusWebFilter(AccessContextResolver resolver, ReactiveJwtDecoder decoder) {
        this.resolver = resolver;
        this.decoder = decoder;
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
                .onErrorResume(org.springframework.security.oauth2.jwt.JwtException.class,
                        ex -> chain.filter(exchange));
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
        return resolver.resolve(jwt.getSubject(), tokenVersion(jwt))
                .doOnNext(access -> exchange.getAttributes().put(ACCESS, access))
                .then()
                .onErrorResume(error -> exchange.getResponse().isCommitted()
                        ? Mono.error(error)
                        : reject(exchange, error));
    }

    /** Reads the {@code ver} claim, tolerating the numeric types a JSON decoder may produce. */
    private static Integer tokenVersion(Jwt jwt) {
        Object value = jwt.getClaim("ver");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /** Writes the failure status with a short plain-text body and completes the response. */
    private static Mono<Void> reject(ServerWebExchange exchange, Throwable error) {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        String message = DEFAULT_MESSAGE;
        if (error instanceof ResponseStatusException failure) {
            HttpStatus resolved = HttpStatus.resolve(failure.getStatusCode().value());
            if (resolved != null) {
                status = resolved;
            }
            String reason = failure.getReason();
            if (reason != null && !reason.isBlank()) {
                message = reason;
            }
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
