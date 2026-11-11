package com.nekocong.text2sql.datasource;

/**
 * Configuration of a single JDBC datasource, bound from a {@code text2sql.datasources.items[]}
 * entry. The registry turns each of these into a cached {@code HikariDataSource}.
 */
public class DataSourceDefinition {

    /** Stable identifier used by APIs, tools and the UI. */
    private String id;

    /** Human-readable name shown in the UI. */
    private String name;

    /** Fully-qualified JDBC driver class (optional; inferred from the URL when omitted). */
    private String driver;

    /** JDBC URL. */
    private String url;

    /** Username. */
    private String username = "";

    /** Password. */
    private String password = "";

    /** Explicit dialect; inferred from the URL when {@code null}. */
    private SqlDialect dialect;

    /** When true, the demo schema/seed scripts are executed against this datasource on startup. */
    private boolean seed = false;

    /** Maximum Hikari pool size. */
    private int maxPoolSize = 5;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public SqlDialect getDialect() {
        return dialect;
    }

    public void setDialect(SqlDialect dialect) {
        this.dialect = dialect;
    }

    public boolean isSeed() {
        return seed;
    }

    public void setSeed(boolean seed) {
        this.seed = seed;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    /**
     * Effective dialect, inferred from the URL when not configured.
     *
     * @return the resolved dialect
     */
    public SqlDialect resolvedDialect() {
        return dialect != null ? dialect : SqlDialect.fromUrl(url);
    }
}
