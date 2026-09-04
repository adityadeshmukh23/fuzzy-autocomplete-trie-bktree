package com.fuzzysearch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the React frontend, which is served from a different origin both in development
 * (Vite on 5173) and in production (a static host, with the API elsewhere).
 *
 * <p>Origins come from configuration rather than being hard-coded to {@code *}: the API is
 * read-only and carries no credentials, so a wildcard would not be dangerous here, but pinning
 * the list keeps the deployed surface explicit and is the habit worth demonstrating.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${fuzzysearch.cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET")
                .maxAge(3600);
    }
}
