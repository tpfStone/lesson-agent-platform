package com.zone.lesoongen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record PlatformSecurityProperties(boolean enabled, boolean registrationEnabled,
        int maxLoginAttempts, int loginLockMinutes) {

    public PlatformSecurityProperties {
        if (maxLoginAttempts <= 0) maxLoginAttempts = 5;
        if (loginLockMinutes <= 0) loginLockMinutes = 15;
    }
}
