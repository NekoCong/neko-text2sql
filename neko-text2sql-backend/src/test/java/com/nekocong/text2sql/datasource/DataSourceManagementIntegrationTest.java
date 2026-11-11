package com.nekocong.text2sql.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Exercises runtime datasource lifecycle management. Uses a fresh context ({@code @DirtiesContext})
 * and removes everything it adds, so the shared registry used by other tests is left untouched.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DataSourceManagementIntegrationTest {

    @Autowired
    private DataSourceRegistry registry;

    @Test
    void managesDatasourceLifecycle() {
        String id = "temp-it-ds";
        try {
            assertThat(registry.definitions()).anyMatch(d -> d.getId().equals("campus"));

            DataSourceDefinition definition = new DataSourceDefinition();
            definition.setId(id);
            definition.setName("Temp IT Datasource");
            definition.setUrl("jdbc:h2:mem:temp_it;DB_CLOSE_DELAY=-1");
            definition.setUsername("sa");
            definition.setDialect(SqlDialect.H2);
            registry.register(definition);

            assertThat(registry.definitions()).anyMatch(d -> d.getId().equals(id));
            assertThat(registry.test(id).ok()).isTrue();

            // A bogus URL must fail fast and report failure rather than throw.
            DataSourceDefinition bad = new DataSourceDefinition();
            bad.setId("bad");
            bad.setUrl("jdbc:invalid:nope");
            assertThat(registry.test(bad).ok()).isFalse();

            // Replace with a new URL, then resolve + query the fresh pool.
            DataSourceDefinition updated = new DataSourceDefinition();
            updated.setName("Temp IT Datasource v2");
            updated.setUrl("jdbc:h2:mem:temp_it_v2;DB_CLOSE_DELAY=-1");
            updated.setUsername("sa");
            registry.replace(id, updated);
            assertThat(registry.definition(id).getName()).isEqualTo("Temp IT Datasource v2");
            assertThat(registry.test(id).ok()).isTrue();

            registry.remove(id);
            assertThat(registry.definitions()).noneMatch(d -> d.getId().equals(id));
            assertThatThrownBy(() -> registry.definition(id)).isInstanceOf(NoSuchElementException.class);
        } finally {
            if (registry.definitions().stream().anyMatch(d -> d.getId().equals(id))) {
                registry.remove(id);
            }
        }
    }

    @Test
    void rejectsDuplicateAndInvalidRegistrations() {
        DataSourceDefinition duplicate = new DataSourceDefinition();
        duplicate.setId("campus");
        duplicate.setUrl("jdbc:h2:mem:x");
        assertThatThrownBy(() -> registry.register(duplicate))
                .isInstanceOf(IllegalArgumentException.class);

        DataSourceDefinition noUrl = new DataSourceDefinition();
        noUrl.setId("no-url");
        assertThatThrownBy(() -> registry.register(noUrl)).isInstanceOf(IllegalArgumentException.class);
    }
}
