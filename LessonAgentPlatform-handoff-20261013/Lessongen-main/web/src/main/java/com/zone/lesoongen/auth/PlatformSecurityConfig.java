package com.zone.lesoongen.auth;

import java.io.IOException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableMethodSecurity
public class PlatformSecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder passwords) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true", matchIfMissing = true)
    SecurityFilterChain secured(HttpSecurity http, InternalServiceTokenFilter internalService,
            PlatformUserRepository userRepository) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        return http
                .addFilterBefore(internalService, AnonymousAuthenticationFilter.class)
                .addFilterAfter(new ActivePlatformUserFilter(userRepository), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/api/auth/register",
                                "/api/auth/login", "/api/auth/csrf").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN", "SERVICE")
                        .anyRequest().permitAll())
                .csrf(config -> config
                        .csrfTokenRepository(csrf)
                        .ignoringRequestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/logout",
                                "/api/platform/lessons/*/simulations"))
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) ->
                                jsonError(response, 401, "AUTHENTICATION_REQUIRED", "请先登录"))
                        .accessDeniedHandler((request, response, error) ->
                                jsonError(response, 403, "ACCESS_DENIED", "无权访问该资源")))
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "false")
    SecurityFilterChain openForTests(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }

    private static void jsonError(HttpServletResponse response, int status,
            String code, String detail) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }
}
