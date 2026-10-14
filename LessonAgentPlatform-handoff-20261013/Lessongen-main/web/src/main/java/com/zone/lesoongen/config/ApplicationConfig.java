package com.zone.lesoongen.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import com.zone.lesoongen.auth.PlatformAuthorizationInterceptor;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({AppProperties.class, PlatformSecurityProperties.class})
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RestClient engineRestClient(AppProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .baseUrl(properties.engineBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("X-Engine-Token", properties.engineInternalToken())
                .build();
    }

    @Bean
    WebMvcConfigurer corsConfigurer(AppProperties properties,
            PlatformAuthorizationInterceptor authorization) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(authorization).addPathPatterns("/api/**")
                        .excludePathPatterns("/api/auth/**");
            }

            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(properties.frontendOrigin())
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("Content-Type", "Idempotency-Key", "Last-Event-ID", "X-XSRF-TOKEN")
                        .exposedHeaders("Content-Disposition", "Digest", "X-Correlation-ID")
                        .allowCredentials(true);
            }
        };
    }
}
