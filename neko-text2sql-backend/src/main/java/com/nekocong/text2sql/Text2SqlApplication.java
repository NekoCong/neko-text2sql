package com.nekocong.text2sql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * Entry point of the Text2SQL backend.
 *
 * <p>A Spring Boot WebFlux application that exposes the AgentScope-powered text-to-SQL agent over
 * REST and Server-Sent Events (SSE). The application database is accessed reactively via R2DBC, so
 * the blocking JDBC {@code DataSource} auto-configuration is excluded; JDBC is used only inside the
 * NL2SQL target-datasource layer (introspection + query execution).
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class Text2SqlApplication {

    /**
     * Starts the Spring Boot application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(Text2SqlApplication.class, args);
    }
}
