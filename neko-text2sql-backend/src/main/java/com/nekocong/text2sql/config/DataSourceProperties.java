package com.nekocong.text2sql.config;

import com.nekocong.text2sql.datasource.DataSourceDefinition;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for all JDBC datasources, bound from the {@code text2sql.datasources.*} prefix.
 *
 * <p>The app is multi-datasource by design: the registry builds one cached pool per entry, and the
 * NL2SQL pipeline / tools address a datasource by its {@code id}.
 */
@ConfigurationProperties(prefix = "text2sql.datasources")
public class DataSourceProperties {

    /** Id of the datasource used when a request does not specify one. */
    private String defaultId;

    /** Configured datasources. */
    private List<DataSourceDefinition> items = new ArrayList<>();

    public String getDefaultId() {
        return defaultId;
    }

    public void setDefaultId(String defaultId) {
        this.defaultId = defaultId;
    }

    public List<DataSourceDefinition> getItems() {
        return items;
    }

    public void setItems(List<DataSourceDefinition> items) {
        this.items = items;
    }
}
