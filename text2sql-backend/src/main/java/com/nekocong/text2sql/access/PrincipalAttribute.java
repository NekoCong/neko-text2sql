package com.nekocong.text2sql.access;

import java.util.Arrays;
import java.util.Optional;

/**
 * The closed catalog of principal attributes a {@code FILTER} policy may reference.
 *
 * <p>Only these values are ever accepted from the admin UI or used to build a predicate; a policy
 * that references anything else is rejected (fail closed).
 */
public enum PrincipalAttribute {
    /** The authenticated user id. */
    USER_ID("userId"),
    /** The login name. */
    USERNAME("username"),
    /** The caller's business-database student id. */
    STUDENT_ID("studentId"),
    /** The caller's student number. */
    STUDENT_NO("studentNo"),
    /** The set of classes in scope (single or multiple). */
    CLASS_IDS("classIds");

    private final String code;

    PrincipalAttribute(String code) {
        this.code = code;
    }

    /** @return the stable code stored in {@code access_data_scope.principal_attribute} */
    public String code() {
        return code;
    }

    /**
     * Resolves a stored code.
     *
     * @param code the stored value
     * @return the attribute, or empty when unknown
     */
    public static Optional<PrincipalAttribute> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(value -> value.code.equalsIgnoreCase(code.trim()))
                .findFirst();
    }
}
