package com.nekocong.text2sql.access;

import java.util.List;

/**
 * The effective policy for one table.
 *
 * @param type whether the table is unrestricted, denied, or filtered
 * @param predicates the OR-ed row predicates (only for {@link Type#FILTER})
 */
public record PolicyDecision(Type type, List<RowPredicate> predicates) {

    /** The decision kind. */
    public enum Type {
        /** Every row is visible. */
        ALL,
        /** The table is denied. */
        DENY,
        /** Only rows matching the predicates are visible. */
        FILTER
    }

    /** Normalizes the predicates. */
    public PolicyDecision {
        predicates = predicates == null ? List.of() : List.copyOf(predicates);
    }

    /** @return an unrestricted decision */
    public static PolicyDecision all() {
        return new PolicyDecision(Type.ALL, List.of());
    }

    /** @return a denied decision */
    public static PolicyDecision deny() {
        return new PolicyDecision(Type.DENY, List.of());
    }

    /**
     * Creates a filtered decision.
     *
     * @param predicates the OR-ed predicates
     * @return the decision
     */
    public static PolicyDecision filter(List<RowPredicate> predicates) {
        return new PolicyDecision(Type.FILTER, predicates);
    }

    /** @return {@code true} when the table is denied */
    public boolean denied() {
        return type == Type.DENY;
    }

    /** @return {@code true} when the table is unrestricted */
    public boolean unrestricted() {
        return type == Type.ALL;
    }

    /** @return {@code true} when the table needs row predicates */
    public boolean filtered() {
        return type == Type.FILTER;
    }
}
