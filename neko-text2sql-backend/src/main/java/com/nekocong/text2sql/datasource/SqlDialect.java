package com.nekocong.text2sql.datasource;

/**
 * SQL dialect of a datasource. Drives prompt hints (dialect naming) and future
 * dialect-specific behaviour. Inferred from the JDBC URL when not configured explicitly.
 */
public enum SqlDialect {
    H2,
    MYSQL,
    POSTGRESQL,
    SQLITE,
    OTHER;

    /**
     * Best-effort dialect detection from a JDBC URL.
     *
     * @param url the JDBC URL (may be {@code null})
     * @return the detected dialect, or {@link #OTHER} when unknown
     */
    public static SqlDialect fromUrl(String url) {
        if (url == null || url.isBlank()) {
            return OTHER;
        }
        String lower = url.toLowerCase();
        if (lower.contains(":h2:")) {
            return H2;
        }
        if (lower.contains(":mysql:") || lower.contains(":mariadb:")) {
            return MYSQL;
        }
        if (lower.contains(":postgresql:")) {
            return POSTGRESQL;
        }
        if (lower.contains(":sqlite:")) {
            return SQLITE;
        }
        return OTHER;
    }
}
