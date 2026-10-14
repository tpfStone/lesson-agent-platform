package com.zone.lesoongen.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.config.PlatformSecurityProperties;

@Service
public class CurrentUserService {
    public static final String LEGACY_USER_ID = "00000000000000000000000000";

    private final PlatformUserRepository users;
    private final PlatformSecurityProperties security;

    public CurrentUserService(PlatformUserRepository users, PlatformSecurityProperties security) {
        this.users = users;
        this.security = security;
    }

    public PlatformUser require() {
        if (!security.enabled()) {
            return users.findById(LEGACY_USER_ID).orElseThrow();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "请先登录");
        }
        return users.findByUsername(authentication.getName())
                .filter(PlatformUser::active)
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED", "登录状态已失效，请重新登录"));
    }
}
