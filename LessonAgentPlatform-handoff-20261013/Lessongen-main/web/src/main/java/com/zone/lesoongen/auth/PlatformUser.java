package com.zone.lesoongen.auth;

import java.time.Instant;

public record PlatformUser(String id, String username, String passwordHash,
        String displayName, String role, String status, Instant createdAt,
        Instant updatedAt, Instant lastLoginAt, int failedLoginCount,
        Instant lockedUntil) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }

    public boolean locked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
