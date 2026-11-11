package com.nekocong.text2sql.access;

/**
 * One direct role assignment of an enabled user, as read for the in-memory last-administrator
 * lockout evaluation.
 *
 * @param userId the enabled user id
 * @param roleId the directly assigned role id
 */
public record UserRoleLink(String userId, String roleId) {
}
