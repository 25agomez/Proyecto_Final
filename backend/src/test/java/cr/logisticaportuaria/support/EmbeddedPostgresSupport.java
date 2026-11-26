package cr.logisticaportuaria.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL real for the integration tests.
 *
 * <p>When Docker is not available (the case of the development environment of
 * this project, see MEMORY.md §9 B-001) the tests start a PostgreSQL embedded
 * in the JVM: it is the same engine, the same extensions and the same SQL
 * that run in production, so the migrations of Flyway are verified for real.
 *
 * <p>The instance is shared by every test class of the run and it is closed
 * when the JVM ends.
 */
public final class EmbeddedPostgresSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddedPostgresSupport.class);

    private static EmbeddedPostgres instance;

    private EmbeddedPostgresSupport() {
        // utility class
    }

    /**
     * @return the shared instance (it is started the first time it is called)
     */
    public static synchronized EmbeddedPostgres get() {
        if (instance == null) {
            instance = start();
            Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedPostgresSupport::close));
        }
        return instance;
    }

    /**
     * @return the JDBC URL of the "postgres" database
     */
    public static String jdbcUrl() {
        return get().getJdbcUrl("postgres", "postgres");
    }

    private static EmbeddedPostgres start() {
        LOGGER.info("Iniciando PostgreSQL embebido para las pruebas de integración...");
        long start = System.currentTimeMillis();
        try {
            return EmbeddedPostgres.builder()
                    .setPort(0)
                    .start();
        } catch (IOException exception) {
            throw new UncheckedIOException("No se pudo iniciar el PostgreSQL embebido.", exception);
        } finally {
            LOGGER.info("PostgreSQL embebido listo en {} ms.", System.currentTimeMillis() - start);
        }
    }

    private static void close() {
        if (instance != null) {
            try {
                instance.close();
            } catch (IOException exception) {
                LOGGER.debug("No se pudo cerrar el PostgreSQL embebido: {}", exception.getMessage());
            }
        }
    }
}
