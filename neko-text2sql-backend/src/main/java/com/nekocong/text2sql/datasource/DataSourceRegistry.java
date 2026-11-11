package com.nekocong.text2sql.datasource;

import com.nekocong.text2sql.config.DataSourceProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/**
 * Owns the set of JDBC datasources the agent may query.
 *
 * <p>Mirrors DB-GPT's {@code ConnectorManager}: each datasource is materialised into a cached
 * {@link HikariDataSource} and looked up by id. Config-defined datasources ({@code
 * text2sql.datasources.items}) are the baseline; additional datasources can be registered/removed at
 * runtime via the management API (runtime ones are in-memory only — re-add on restart).
 */
@Component
public class DataSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRegistry.class);

    private final DataSourceProperties properties;
    private final Map<String, DataSourceDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, HikariDataSource> pools = new LinkedHashMap<>();
    private String defaultId;

    /**
     * Creates the registry.
     *
     * @param properties the configured datasources
     */
    public DataSourceRegistry(DataSourceProperties properties) {
        this.properties = properties;
    }

    /** Builds and seeds every configured datasource. */
    @PostConstruct
    synchronized void initialize() {
        for (DataSourceDefinition definition : properties.getItems()) {
            if (definition.getId() == null || definition.getId().isBlank()) {
                throw new IllegalStateException("A datasource entry is missing its 'id'");
            }
            definitions.put(definition.getId(), definition);
        }
        if (definitions.isEmpty()) {
            throw new IllegalStateException(
                    "No datasources configured under 'text2sql.datasources.items'");
        }
        String configuredDefault = properties.getDefaultId();
        defaultId =
                (configuredDefault != null && definitions.containsKey(configuredDefault))
                        ? configuredDefault
                        : definitions.keySet().iterator().next();

        for (DataSourceDefinition definition : definitions.values()) {
            HikariDataSource pool = build(definition);
            pools.put(definition.getId(), pool);
            if (definition.isSeed()) {
                seed(definition.getId(), pool);
            }
        }
        log.info(
                "DataSourceRegistry ready: default='{}', datasources={}",
                defaultId,
                definitions.keySet());
    }

    /**
     * Resolves a datasource by id.
     *
     * @param id the datasource id, or {@code null}/blank for the default
     * @return the pooled datasource
     * @throws NoSuchElementException when the id is unknown
     */
    public synchronized DataSource resolve(String id) {
        return pool(id);
    }

    /**
     * Returns the definition for a datasource id.
     *
     * @param id the datasource id, or {@code null}/blank for the default
     * @return the definition
     * @throws NoSuchElementException when the id is unknown
     */
    public synchronized DataSourceDefinition definition(String id) {
        String key = normalize(id);
        DataSourceDefinition definition = definitions.get(key);
        if (definition == null) {
            throw new NoSuchElementException("Unknown datasource id: " + key);
        }
        return definition;
    }

    /**
     * Returns all configured datasource definitions, in registration order.
     *
     * @return the definitions
     */
    public synchronized List<DataSourceDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    /**
     * Returns the default datasource id.
     *
     * @return the default id
     */
    public synchronized String defaultId() {
        return defaultId;
    }

    /**
     * Registers a new datasource at runtime.
     *
     * @param definition the definition (id and url required)
     * @return the registered definition
     * @throws IllegalArgumentException when invalid or the id already exists
     */
    public synchronized DataSourceDefinition register(DataSourceDefinition definition) {
        validate(definition);
        if (definitions.containsKey(definition.getId())) {
            throw new IllegalArgumentException("Datasource already exists: " + definition.getId());
        }
        HikariDataSource pool = build(definition);
        pools.put(definition.getId(), pool);
        definitions.put(definition.getId(), definition);
        if (definition.isSeed()) {
            seed(definition.getId(), pool);
        }
        log.info("Registered datasource '{}' at runtime", definition.getId());
        return definition;
    }

    /**
     * Replaces an existing datasource definition, rebuilding its pool.
     *
     * @param id the datasource id (also forced as the definition id)
     * @param definition the new definition
     * @return the updated definition
     * @throws NoSuchElementException when the id is unknown
     * @throws IllegalArgumentException when the definition is invalid
     */
    public synchronized DataSourceDefinition replace(String id, DataSourceDefinition definition) {
        String key = normalize(id);
        if (!definitions.containsKey(key)) {
            throw new NoSuchElementException("Unknown datasource id: " + key);
        }
        definition.setId(key);
        validate(definition);
        // Build the replacement first so a failure leaves the existing datasource intact.
        HikariDataSource pool = build(definition);
        HikariDataSource old = pools.put(key, pool);
        definitions.put(key, definition);
        if (old != null) {
            old.close();
        }
        if (definition.isSeed()) {
            seed(key, pool);
        }
        log.info("Replaced datasource '{}'", key);
        return definition;
    }

    /**
     * Removes a datasource, closing its pool.
     *
     * @param id the datasource id (or {@code null}/blank for the default)
     * @throws NoSuchElementException when the id is unknown
     * @throws IllegalStateException when it is the last remaining datasource
     */
    public synchronized void remove(String id) {
        String key = normalize(id);
        if (!definitions.containsKey(key)) {
            throw new NoSuchElementException("Unknown datasource id: " + key);
        }
        if (definitions.size() <= 1) {
            throw new IllegalStateException("Cannot remove the last datasource");
        }
        HikariDataSource pool = pools.remove(key);
        definitions.remove(key);
        if (pool != null) {
            pool.close();
        }
        if (key.equals(defaultId)) {
            defaultId = definitions.keySet().iterator().next();
        }
        log.info("Removed datasource '{}'", key);
    }

    /**
     * Tests connectivity for an arbitrary definition without registering it.
     *
     * @param definition the candidate definition
     * @return the test result
     */
    public ConnectionTestResult test(DataSourceDefinition definition) {
        long start = System.currentTimeMillis();
        try (Connection connection =
                DriverManager.getConnection(
                        definition.getUrl(),
                        definition.getUsername(),
                        definition.getPassword() == null ? "" : definition.getPassword())) {
            ping(connection);
            return new ConnectionTestResult(true, "连接成功", System.currentTimeMillis() - start);
        } catch (SQLException | RuntimeException ex) {
            return new ConnectionTestResult(false, rootMessage(ex), System.currentTimeMillis() - start);
        }
    }

    /**
     * Tests connectivity for a registered datasource.
     *
     * @param id the datasource id (or {@code null}/blank for the default)
     * @return the test result
     */
    public synchronized ConnectionTestResult test(String id) {
        long start = System.currentTimeMillis();
        try (Connection connection = pool(id).getConnection()) {
            ping(connection);
            return new ConnectionTestResult(true, "连接成功", System.currentTimeMillis() - start);
        } catch (SQLException | RuntimeException ex) {
            return new ConnectionTestResult(false, rootMessage(ex), System.currentTimeMillis() - start);
        }
    }

    private static void ping(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            resultSet.next();
        }
    }

    private HikariDataSource pool(String id) {
        String key = normalize(id);
        HikariDataSource pool = pools.get(key);
        if (pool == null) {
            throw new NoSuchElementException("Unknown datasource id: " + key);
        }
        return pool;
    }

    private String normalize(String id) {
        String key = (id == null || id.isBlank()) ? defaultId : id;
        if (key == null) {
            throw new NoSuchElementException("No datasource configured");
        }
        return key;
    }

    private static void validate(DataSourceDefinition definition) {
        if (definition.getId() == null || definition.getId().isBlank()) {
            throw new IllegalArgumentException("'id' is required");
        }
        if (definition.getUrl() == null || definition.getUrl().isBlank()) {
            throw new IllegalArgumentException("'url' is required");
        }
    }

    private HikariDataSource build(DataSourceDefinition definition) {
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(definition.getUrl());
            if (notBlank(definition.getDriver())) {
                config.setDriverClassName(definition.getDriver());
            }
            if (notBlank(definition.getUsername())) {
                config.setUsername(definition.getUsername());
            }
            config.setPassword(definition.getPassword() == null ? "" : definition.getPassword());
            config.setPoolName("t2s-" + definition.getId());
            config.setMaximumPoolSize(definition.getMaxPoolSize());
            // Read-only is enforced per query in SqlExecutor; the pool itself must stay writable so
            // seeding and metadata reflection work.
            config.setReadOnly(false);
            return new HikariDataSource(config);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "Failed to create pool for '" + definition.getId() + "': " + rootMessage(ex), ex);
        }
    }

    private void seed(String id, DataSource pool) {
        try (Connection connection = pool.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/data.sql"));
            log.info("Seeded datasource '{}' with demo schema + data", id);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to seed datasource '" + id + "'", ex);
        }
    }

    /** Closes every pooled datasource. */
    @PreDestroy
    synchronized void shutdown() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
