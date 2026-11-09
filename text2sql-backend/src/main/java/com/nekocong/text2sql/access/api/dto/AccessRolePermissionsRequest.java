package com.nekocong.text2sql.access.api.dto;

import java.util.List;

/**
 * Request body for replacing a role's permission set.
 *
 * <p>The list is the <em>complete</em> new set, not a delta: a key absent from it is revoked. An
 * empty (or {@code null}) list clears every permission of the role. Duplicates are de-duplicated and
 * every key must exist in the permission catalog.
 *
 * @param permissions the permission keys the role should hold
 */
public record AccessRolePermissionsRequest(List<String> permissions) {
}
