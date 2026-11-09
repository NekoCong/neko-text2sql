package com.nekocong.text2sql.controller;

import com.nekocong.text2sql.access.AccessContextResolver;
import com.nekocong.text2sql.controller.dto.AuthResponse;
import com.nekocong.text2sql.controller.dto.LoginRequest;
import com.nekocong.text2sql.controller.dto.RegisterRequest;
import com.nekocong.text2sql.controller.dto.UserView;
import com.nekocong.text2sql.service.AuthResult;
import com.nekocong.text2sql.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Controller layer for authentication. Delegates all business logic to {@link AuthService}.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AccessContextResolver accessContext;

    /**
     * Creates the controller.
     *
     * @param authService the authentication service
     * @param accessContext the resolver that computes the caller's effective roles/permissions
     */
    public AuthController(AuthService authService, AccessContextResolver accessContext) {
        this.authService = authService;
        this.accessContext = accessContext;
    }

    /**
     * Registers a new user and returns a token.
     *
     * @param request the registration request
     * @return the auth response
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AuthResponse> register(@RequestBody RegisterRequest request) {
        return authService
                .register(request.username(), request.password(), request.displayName())
                .flatMap(this::toResponse);
    }

    /**
     * Authenticates a user and returns a token.
     *
     * @param request the login request
     * @return the auth response
     */
    @PostMapping("/login")
    public Mono<AuthResponse> login(@RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password()).flatMap(this::toResponse);
    }

    /**
     * Returns the currently authenticated user.
     *
     * @param jwt the bearer token
     * @return the user
     */
    @GetMapping("/me")
    public Mono<UserView> me(@AuthenticationPrincipal Jwt jwt) {
        return authService
                .requireUser(jwt.getSubject())
                .flatMap(user -> toView(user.id(), user.username(), user.displayName(), user.role()));
    }

    /**
     * Resolves the caller's effective roles and permissions from the access-control tables and
     * builds the enriched view, keeping the legacy {@code role} field display-only.
     */
    private Mono<UserView> toView(String userId, String username, String displayName, String role) {
        return Mono.zip(
                        accessContext.effectiveRoleCodes(userId),
                        accessContext.effectivePermissions(userId))
                .map(tuple -> new UserView(
                        userId,
                        username,
                        displayName,
                        role,
                        tuple.getT1(),
                        tuple.getT2().stream().sorted().toList()));
    }

    private Mono<AuthResponse> toResponse(AuthResult result) {
        return toView(result.userId(), result.username(), result.displayName(), result.role())
                .map(view -> new AuthResponse(result.token(), result.expiresIn(), view));
    }
}
