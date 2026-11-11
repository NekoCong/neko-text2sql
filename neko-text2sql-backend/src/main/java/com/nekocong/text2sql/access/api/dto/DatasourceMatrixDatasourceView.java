package com.nekocong.text2sql.access.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One datasource row in the datasource matrix.
 *
 * @param id the datasource id
 * @param name the display name
 * @param defaultDatasource whether this is the configured default datasource (serialised as {@code
 *     "default"} to preserve the public contract)
 */
public record DatasourceMatrixDatasourceView(
        String id, String name, @JsonProperty("default") boolean defaultDatasource) {}
