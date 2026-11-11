package com.nekocong.text2sql.access.api.dto;

/**
 * A role reference inside {@link AccessUserView}: the id plus the display fields, so a client can
 * render the user's roles without a second round trip.
 *
 * @param id the role id
 * @param code the stable code
 * @param name the display name
 */
public record AccessUserRoleView(String id, String code, String name) {}
