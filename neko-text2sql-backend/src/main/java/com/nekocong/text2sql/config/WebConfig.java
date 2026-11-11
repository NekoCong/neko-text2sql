package com.nekocong.text2sql.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * WebFlux level configuration.
 *
 * <p>Enables permissive CORS for the {@code /api/**} endpoints so the Vue dev server can call the
 * backend directly (the Vite proxy makes this unnecessary, but it removes a common stumbling
 * block).
 */
@Configuration
public class WebConfig implements WebFluxConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
