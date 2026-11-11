package com.nekocong.text2sql.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nekocong.text2sql.datasource.DataSourceDefinition;

/**
 * A datasource as returned to clients.
 *
 * @param id the id
 * @param name the display name
 * @param dialect the SQL dialect
 * @param url the JDBC URL
 * @param defaultDataSource whether it is the default (serialised as {@code "default"} to preserve
 *     the public contract)
 */
public record DataSourceView(
        String id,
        String name,
        String dialect,
        String url,
        @JsonProperty("default") boolean defaultDataSource) {

    /**
     * Maps a definition to its view.
     *
     * @param definition the definition
     * @param defaultId the default datasource id
     * @return the view
     */
    public static DataSourceView of(DataSourceDefinition definition, String defaultId) {
        return new DataSourceView(
                definition.getId(),
                definition.getName() == null ? definition.getId() : definition.getName(),
                definition.resolvedDialect().name(),
                definition.getUrl(),
                definition.getId().equals(defaultId));
    }
}
