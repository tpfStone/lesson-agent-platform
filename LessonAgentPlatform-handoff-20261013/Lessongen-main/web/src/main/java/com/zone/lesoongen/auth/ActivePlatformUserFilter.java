package com.zone.lesoongen.auth;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Makes account disabling effective for sessions that were already authenticated. */
public class ActivePlatformUserFilter extends OncePerRequestFilter {
    private final PlatformUserRepository users;

    public ActivePlatformUserFilter(PlatformUserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())
                && authentication.getAuthorities().stream()
                        .noneMatch(authority -> "ROLE_SERVICE".equals(authority.getAuthority()))) {
            boolean active = users.findByUsername(authentication.getName())
                    .map(PlatformUser::active).orElse(false);
            if (!active) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) request.getSession(false).invalidate();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setCharacterEncoding("UTF-8");
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.getWriter().write(
                        "{\"code\":\"ACCOUNT_DISABLED\",\"detail\":\"账户已被禁用，请联系管理员\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
