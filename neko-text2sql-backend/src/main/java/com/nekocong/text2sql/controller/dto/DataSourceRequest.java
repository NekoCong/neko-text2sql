package com.nekocong.text2sql.controller.dto;

import com.nekocong.text2sql.datasource.DataSourceDefinition;
import com.nekocong.text2sql.datasource.SqlDialect;

/**
 * Request body for create/update datasource operations.
 *
 * @param id stable identifier (required)
 * @param name display name
 * @param driver JDBC driver class (optional; inferred from the URL when null)
 * @param url JDBC URL (required)
 * @param username username
 * @param password password
 * @param dialect SQL dialect name (optional; inferred from the URL when null/unknown)
 * @param seed run the demo seed scripts on registration
 * @param maxPoolSize Hikari maximum pool size
 */
public record DataSourceRequest(
        String id,
        String name,
        String driver,
        String url,
        String username,
        String password,
        String dialect,
        Boolean seed,
        Integer maxPoolSize) {

    /**
     * Converts the request to a registry definition.
     *
     * @return the definition
     */
    public DataSourceDefinition toDefinition() {
        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(id);
        definition.setName(name);
        definition.setDriver(driver);
        definition.setUrl(url);
        definition.setUsername(username == null ? "" : username);
        definition.setPassword(password == null ? "" : password);
        definition.setDialect(parseDialect(dialect));
        definition.setSeed(Boolean.TRUE.equals(seed));
        if (maxPoolSize != null && maxPoolSize > 0) {
            definition.setMaxPoolSize(maxPoolSize);
        }
        return definition;
    }

    private static SqlDialect parseDialect(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SqlDialect.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null; // unknown name -> infer from the URL
        }
    }
}
