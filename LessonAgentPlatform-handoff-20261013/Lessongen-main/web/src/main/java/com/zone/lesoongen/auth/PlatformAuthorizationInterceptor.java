package com.zone.lesoongen.auth;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class PlatformAuthorizationInterceptor implements HandlerInterceptor {
    private final PlatformAccessService access;

    public PlatformAuthorizationInterceptor(PlatformAccessService access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod) || isInternalService()) return true;
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables == null) return true;
        String uri = request.getRequestURI();
        if (variables.containsKey("lessonId")) access.requireOwnedLesson(variables.get("lessonId"));
        if (variables.containsKey("launchId")) access.requireOwnedSimulationLaunch(variables.get("launchId"));
        if (variables.containsKey("jobId") && uri.startsWith("/api/v1/")) access.requireOwnedJob(variables.get("jobId"));
        if (variables.containsKey("sessionId")
                && (uri.startsWith("/api/platform/navigation/") || uri.startsWith("/api/platform/f4/"))) {
            access.requireOwnedNavigationSession(variables.get("sessionId"));
        }
        if (variables.containsKey("batchId")) access.requireOwnedExternalTask("F1_BATCH", variables.get("batchId"));
        if (variables.containsKey("comparisonId")) {
            access.requireOwnedExternalTask("F1_COMPARISON", variables.get("comparisonId"));
        }
        return true;
    }

    private static boolean isInternalService() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(value -> "ROLE_SERVICE".equals(value.getAuthority()));
    }
}
