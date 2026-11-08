package com.nekocong.text2sql.access;

import java.util.Set;

/**
 * The principal attributes resolved from {@code user_scope} and the account row.
 *
 * @param userId the authenticated user id
 * @param username the login name
 * @param studentId the business-database student id, or {@code null}
 * @param studentNo the student number, or {@code null}
 * @param classIds the classes in scope (never {@code null})
 */
public record PrincipalAttributes(
        String userId, String username, Long studentId, String studentNo, Set<Long> classIds) {

    /** Normalizes the class set so the value is immutable. */
    public PrincipalAttributes {
        classIds = classIds == null ? Set.of() : Set.copyOf(classIds);
    }
}
