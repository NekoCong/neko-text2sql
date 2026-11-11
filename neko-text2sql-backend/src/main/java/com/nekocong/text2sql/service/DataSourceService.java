package com.nekocong.text2sql.service;

import com.nekocong.text2sql.datasource.ConnectionTestResult;
import com.nekocong.text2sql.datasource.DataSourceDefinition;
import com.nekocong.text2sql.datasource.DataSourceRegistry;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Service layer for datasource management. Wraps the {@link DataSourceRegistry} infrastructure so
 * the controller depends only on this service.
 */
@Service
public class DataSourceService {

    private final DataSourceRegistry registry;

    /**
     * Creates the service.
     *
     * @param registry the datasource registry
     */
    public DataSourceService(DataSourceRegistry registry) {
        this.registry = registry;
    }

    /**
     * Lists all datasources.
     *
     * @return the definitions
     */
    public List<DataSourceDefinition> list() {
        return registry.definitions();
    }

    /**
     * Returns the default datasource id.
     *
     * @return the default id
     */
    public String defaultId() {
        return registry.defaultId();
    }

    /**
     * Registers a datasource.
     *
     * @param definition the definition
     * @return the registered definition
     */
    public DataSourceDefinition register(DataSourceDefinition definition) {
        return registry.register(definition);
    }

    /**
     * Replaces a datasource.
     *
     * @param id the datasource id
     * @param definition the new definition
     * @return the updated definition
     */
    public DataSourceDefinition replace(String id, DataSourceDefinition definition) {
        return registry.replace(id, definition);
    }

    /**
     * Removes a datasource.
     *
     * @param id the datasource id
     */
    public void remove(String id) {
        registry.remove(id);
    }

    /**
     * Tests a registered datasource.
     *
     * @param id the datasource id
     * @return the test result
     */
    public ConnectionTestResult test(String id) {
        return registry.test(id);
    }

    /**
     * Tests a candidate datasource.
     *
     * @param definition the candidate definition
     * @return the test result
     */
    public ConnectionTestResult test(DataSourceDefinition definition) {
        return registry.test(definition);
    }
}
