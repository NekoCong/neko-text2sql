package com.nekocong.text2sql.access;

/** One resolved row predicate: {@code column = principal.attribute} or {@code column IN (...)}. */
public record RowPredicate(String column, Operator operator, PrincipalAttribute attribute) {

    /** How the principal attribute is compared with the column. */
    public enum Operator {
        /** Single value equality. */
        EQ,
        /** Set membership. */
        IN
    }
}
