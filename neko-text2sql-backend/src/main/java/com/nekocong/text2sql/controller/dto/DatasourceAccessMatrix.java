package com.nekocong.text2sql.controller.dto;

import java.util.List;

/**
 * The datasource access matrix shown to administrators.
 *
 * @param roles the roles that can be granted (admins are unrestricted and never listed)
 * @param datasources every configured datasource with the roles granted on it
 */
public record DatasourceAccessMatrix(List<String> roles, List<DatasourceAccessView> datasources) {}
