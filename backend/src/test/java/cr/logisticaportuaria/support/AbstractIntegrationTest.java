package cr.logisticaportuaria.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class of the integration tests: starts a real PostgreSQL (embedded,
 * see {@link EmbeddedPostgresSupport}) and points the datasource of Spring to
 * it.
 */
public abstract class AbstractIntegrationTest {

    /**
     * Registers the datasource of the embedded PostgreSQL.
     *
     * @param registry registry of the dynamic properties
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.enabled", () -> "true");
    }
}
