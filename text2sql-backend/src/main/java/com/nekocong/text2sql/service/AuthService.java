package com.nekocong.text2sql.service;

import com.nekocong.text2sql.access.AccessControlRepository;
import com.nekocong.text2sql.repository.UserRepository;
import com.nekocong.text2sql.security.JwtService;
import com.nekocong.text2sql.security.Roles;
import com.nekocong.text2sql.user.AppUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Service layer for authentication and user accounts (reactive).
 *
 * <p>Owns the business rules (uniqueness, password policy, credential verification); persistence is
 * delegated to {@link UserRepository} and token issuance to {@link JwtService}. CPU-bound password
 * hashing runs on a bounded-elastic scheduler so it never blocks the event loop.
 *
 * <p>Self-service registration always creates a {@link Roles#STUDENT} and assigns the built-in
 * {@code student} role in the access-control tables; elevated roles are assigned out of band, so the
 * registration endpoint cannot be used for privilege escalation. A freshly registered student has
 * no data scope configured until one exists, so the data-permission layer rejects its queries in the
 * meantime.
 */
@Service
public class AuthService {

    private static final int MIN_PASSWORD_LENGTH = 6;

    private final UserRepository users;
    private final AccessControlRepository access;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Creates the service.
     *
     * @param users the user repository
     * @param access the access-control repository used to assign the built-in role
     * @param passwordEncoder the password encoder
     * @param jwtService the token issuer
     */
    public AuthService(
            UserRepository users,
            AccessControlRepository access,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.users = users;
        this.access = access;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Registers a new user.
     *
     * @param username the desired username
     * @param password the raw password
     * @param displayName the optional display name
     * @return the authentication result
     */
    public Mono<AuthResult> register(String username, String password, String displayName) {
        return Mono.defer(
                () -> {
                    String name = require(username, "用户名不能为空");
                    String secret = require(password, "密码不能为空");
                    if (secret.length() < MIN_PASSWORD_LENGTH) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "密码至少 " + MIN_PASSWORD_LENGTH + " 位");
                    }
                    String display =
                            (displayName == null || displayName.isBlank()) ? name : displayName.trim();
                    return users.findByUsername(name)
                            .flatMap(
                                    existing ->
                                            Mono.<AuthResult>error(
                                                    new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在")))
                            .switchIfEmpty(
                                    Mono.defer(
                                            () ->
                                                    encode(secret)
                                                            .flatMap(hash -> users.insert(name, hash, display, Roles.STUDENT))
                                                            .flatMap(
                                                                    user ->
                                                                            access.assignRoleByCode(user.id(), Roles.STUDENT)
                                                                                    .onErrorMap(
                                                                                            error ->
                                                                                                    new ResponseStatusException(
                                                                                                            HttpStatus.INTERNAL_SERVER_ERROR,
                                                                                                            "内置 student 角色缺失"))
                                                                                    .thenReturn(user))
                                                            .map(this::issue)));
                });
    }

    /**
     * Authenticates a user.
     *
     * @param username the username
     * @param password the raw password
     * @return the authentication result
     */
    public Mono<AuthResult> login(String username, String password) {
        return users.findByUsername(username == null ? "" : username)
                .switchIfEmpty(Mono.error(invalidCredentials()))
                .flatMap(
                        user -> {
                            if (!user.enabled()) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "账号已停用，请联系管理员"));
                            }
                            return verify(password, user)
                                    .filter(Boolean::booleanValue)
                                    .map(ok -> issue(user))
                                    .switchIfEmpty(Mono.error(invalidCredentials()));
                        });
    }

    /**
     * Loads a user by id, failing with 401 when absent.
     *
     * @param userId the user id
     * @return the user
     */
    public Mono<AppUser> requireUser(String userId) {
        return users
                .findById(userId)
                .switchIfEmpty(
                        Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在")));
    }

    private Mono<String> encode(String raw) {
        return Mono.fromCallable(() -> passwordEncoder.encode(raw)).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Boolean> verify(String raw, AppUser user) {
        return Mono.fromCallable(() -> raw != null && passwordEncoder.matches(raw, user.passwordHash()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private AuthResult issue(AppUser user) {
        String canonical = Roles.normalize(user.role());
        return new AuthResult(
                jwtService.issue(user.id(), user.username(), user.role(), user.tokenVersion()),
                jwtService.ttlSeconds(),
                user.id(),
                user.username(),
                user.displayName(),
                canonical == null ? user.role() : canonical);
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    }
}
