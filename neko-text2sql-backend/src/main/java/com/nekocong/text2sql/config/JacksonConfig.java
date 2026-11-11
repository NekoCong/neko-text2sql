package com.nekocong.text2sql.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a shared Jackson {@link ObjectMapper}.
 *
 * <p>The WebFlux stack in this Spring Boot version does not publish an {@code ObjectMapper} bean, so
 * components that serialise structured payloads (SSE events, JSON tool results) declare one here
 * explicitly. Modules (e.g. Java time) are auto-registered when present.
 */
@Configuration
public class JacksonConfig {

    /**
     * The application-wide JSON mapper.
     *
     * @return a Jackson mapper with modules discovered on the classpath
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
