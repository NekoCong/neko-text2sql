package com.nekocong.text2sql.controller.dto;

import java.util.List;

/**
 * One datasource row in the access matrix.
 *
 * @param id the datasource id
 * @param name the display name
 * @param defaultDatasource whether this is the configured default datasource
 * @param grantedRoles the roles currently granted access
 */
public record DatasourceAccessView(
        String id, String name, boolean defaultDatasource, List<String> grantedRoles) {}
