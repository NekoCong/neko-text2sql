package com.nekocong.text2sql.access.api.dto;

import java.util.List;

/**
 * Request body for replacing a role's datasource grant set.
 *
 * <p>The list is the <em>complete</em> new set, not a delta: an id absent from it is revoked. An
 * empty list clears every grant of the role. Duplicates are de-duplicated and every id must exist in
 * the datasource registry.
 *
 * @param datasourceIds the datasource ids the role should be able to query
 */
public record RoleDatasourcesRequest(List<String> datasourceIds) {}
