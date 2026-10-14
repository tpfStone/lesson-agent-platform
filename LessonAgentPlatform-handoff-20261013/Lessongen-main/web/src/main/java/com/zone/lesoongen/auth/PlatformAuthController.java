package com.zone.lesoongen.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.zone.lesoongen.application.AppException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
public class PlatformAuthController {
    private final PlatformUserService users;
    private final CurrentUserService currentUser;
    private final PlatformUserRepository repository;
    private final LoginAuditService audits;
    private final AuthenticationManager authenticationManager;
    private final com.zone.lesoongen.config.PlatformSecurityProperties security;
    private final HttpSessionSecurityContextRepository securityContexts =
            new HttpSessionSecurityContextRepository();

    public PlatformAuthController(PlatformUserService users, CurrentUserService currentUser,
            PlatformUserRepository repository, LoginAuditService audits,
            AuthenticationManager authenticationManager,
            com.zone.lesoongen.config.PlatformSecurityProperties security) {
        this.users = users;
        this.currentUser = currentUser;
        this.repository = repository;
        this.audits = audits;
        this.authenticationManager = authenticationManager;
        this.security = security;
    }

    public record RegisterRequest(@NotBlank @Size(max = 64) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 100) String displayName) {}

    public record LoginRequest(@NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 72) String password) {}

    public record ProfileRequest(@NotBlank @Size(max = 100) String displayName) {}

    public record PasswordRequest(@NotBlank @Size(max = 72) String currentPassword,
            @NotBlank @Size(min = 8, max = 72) String newPassword) {}

    public record UserView(String id, String username, String displayName,
            String role, Instant lastLoginAt) {
        static UserView from(PlatformUser user) {
            return new UserView(user.id(), user.username(), user.displayName(),
                    user.role(), user.lastLoginAt());
        }
    }

    public record CsrfView(String headerName, String parameterName, String token) {}

    @PostMapping("/register")
    public ResponseEntity<UserView> register(@Valid @RequestBody RegisterRequest request) {
        PlatformUser created = users.register(request.username(), request.password(), request.displayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserView.from(created));
    }

    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String username = PlatformUserService.normalize(request.username());
        Instant now = Instant.now();
        PlatformUser candidate = repository.findByUsername(username).orElse(null);
        if (candidate != null && candidate.active() && candidate.locked(now)) {
            audits.record(candidate.id(), username, false, clientIp(servletRequest));
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS,
                    "LOGIN_TEMPORARILY_LOCKED", "登录失败次数过多，请稍后再试");
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, request.password()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContexts.saveContext(context, servletRequest, servletResponse);
            PlatformUser user = users.requireActive(username);
            repository.updateLastLogin(user.id(), now);
            audits.record(user.id(), username, true, clientIp(servletRequest));
            return UserView.from(repository.findById(user.id()).orElseThrow());
        } catch (AuthenticationException error) {
            String userId = candidate == null ? null : candidate.id();
            audits.record(userId, username, false, clientIp(servletRequest));
            if (candidate != null && candidate.active()) {
                int failures = (candidate.lockedUntil() != null ? 0 : candidate.failedLoginCount()) + 1;
                Instant lockedUntil = failures >= security.maxLoginAttempts()
                        ? now.plus(security.loginLockMinutes(), ChronoUnit.MINUTES)
                        : null;
                repository.recordFailedLogin(candidate.id(), failures, lockedUntil, now);
            }
            throw new AppException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "用户名或密码错误");
        }
    }

    @GetMapping("/me")
    public UserView me() {
        return UserView.from(currentUser.require());
    }

    @PatchMapping("/me")
    public UserView updateProfile(@Valid @RequestBody ProfileRequest request) {
        PlatformUser user = currentUser.require();
        return UserView.from(users.updateDisplayName(user.id(), request.displayName()));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordRequest request) {
        users.changePassword(currentUser.require(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token) {
        return new CsrfView(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }
}
