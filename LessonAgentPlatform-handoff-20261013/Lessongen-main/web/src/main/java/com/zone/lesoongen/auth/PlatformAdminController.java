package com.zone.lesoongen.auth;

import java.time.Instant;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class PlatformAdminController {
    private final PlatformUserRepository repository;
    private final PlatformUserService users;
    private final CurrentUserService currentUser;
    private final LoginAuditService audits;

    public PlatformAdminController(PlatformUserRepository repository, PlatformUserService users,
            CurrentUserService currentUser, LoginAuditService audits) {
        this.repository = repository;
        this.users = users;
        this.currentUser = currentUser;
        this.audits = audits;
    }

    public record AdminUserView(String id, String username, String displayName,
            String role, String status, Instant createdAt, Instant updatedAt,
            Instant lastLoginAt, int failedLoginCount, Instant lockedUntil) {
        static AdminUserView from(PlatformUser user) {
            return new AdminUserView(user.id(), user.username(), user.displayName(),
                    user.role(), user.status(), user.createdAt(), user.updatedAt(),
                    user.lastLoginAt(), user.failedLoginCount(), user.lockedUntil());
        }
    }

    public record StatusRequest(
            @NotNull @Pattern(regexp = "ACTIVE|DISABLED") String status) {}

    @GetMapping("/users")
    public List<AdminUserView> listUsers() {
        return repository.findAll().stream().map(AdminUserView::from).toList();
    }

    @PatchMapping("/users/{userId}/status")
    public AdminUserView updateStatus(@PathVariable String userId,
            @Valid @RequestBody StatusRequest request) {
        return AdminUserView.from(users.setStatus(userId, request.status(), currentUser.require().id()));
    }

    @GetMapping("/login-audits")
    public List<LoginAuditService.AuditView> loginAudits(
            @RequestParam(defaultValue = "100") int limit) {
        return audits.recent(limit);
    }
}
