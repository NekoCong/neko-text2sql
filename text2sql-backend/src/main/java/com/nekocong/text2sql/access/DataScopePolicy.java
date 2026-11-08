package com.nekocong.text2sql.access;

/** The per-role, per-table data policy. */
public enum DataScopePolicy {
    /** Every row is visible. */
    ALL,
    /** Only rows matching the configured principal attribute are visible. */
    FILTER,
    /** The table is not visible. */
    DENY
}
