package cr.logisticaportuaria;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the LogisticaPortuaria CR backend.
 *
 * <p>The application is a modular monolith: the packages {@code gate},
 * {@code yard}, {@code optimizer}, {@code congestion}, {@code fleet} and
 * {@code shared} live in the same deployable unit, with the layers
 * {@code api}, {@code application}, {@code domain} and {@code infrastructure}
 * inside each module.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LogisticaPortuariaApplication {

    /**
     * Starts the application.
     *
     * @param args arguments of the command line
     */
    public static void main(String[] args) {
        SpringApplication.run(LogisticaPortuariaApplication.class, args);
    }
}
