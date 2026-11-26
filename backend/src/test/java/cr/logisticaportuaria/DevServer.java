package cr.logisticaportuaria;

import cr.logisticaportuaria.support.EmbeddedPostgresSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Servidor de desarrollo local para ejecutar el backend sin necesidad de Docker externo.
 * Utiliza PostgreSQL embebido (Zonky) con Flyway V1 y V2 cargados, exponiendo el puerto 8080.
 */
public final class DevServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DevServer.class);

    private DevServer() {
        // utility class
    }

    public static void main(String[] args) {
        String jdbcUrl = EmbeddedPostgresSupport.jdbcUrl();
        LOGGER.info("Iniciando LogisticaPortuaria CR Backend con PostgreSQL embebido...");
        LOGGER.info("Base de datos lista en: {}", jdbcUrl);

        // System properties override profile YAMLs (like application-dev.yml)
        System.setProperty("spring.datasource.url", jdbcUrl);
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        System.setProperty("server.port", "8080");
        System.setProperty("app.mqtt.enabled", "false");
        System.setProperty("app.edge.open-ingestion", "true");
        System.setProperty("spring.flyway.enabled", "true");

        ConfigurableApplicationContext context = new SpringApplicationBuilder(LogisticaPortuariaApplication.class)
                .profiles("dev", "test")
                .run(args);

        LOGGER.info("================================================================================");
        LOGGER.info(" Backend LogisticaPortuaria CR iniciado exitosamente en http://localhost:8080 ");
        LOGGER.info(" Swagger UI: http://localhost:8080/swagger-ui.html                             ");
        LOGGER.info(" Actuator:   http://localhost:8080/actuator/health                             ");
        LOGGER.info("================================================================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Apagando servidor de desarrollo...");
            context.close();
        }));
    }
}
