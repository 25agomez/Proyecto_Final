package cr.logisticaportuaria.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentation of the API (springdoc-openapi).
 *
 * <p>The generated document lives in {@code /v3/api-docs} and the interactive
 * console in {@code /swagger-ui.html}. The authoritative contract of the
 * project is {@code docs/api/openapi.yaml} (owned by the architect); the
 * generated document is verified against it by the contract test.
 */
@Configuration
public class OpenApiConfig {

    /**
     * @return the description of the API
     */
    @Bean
    public OpenAPI logisticaPortuariaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LogisticaPortuaria CR - API")
                        .version("1.0.0")
                        .description("""
                                API de puertas, citas, bitácora digital y datos maestros del patio.
                                Las respuestas de error usan `ProblemDetail` (RFC 7807) y los mensajes
                                están en español (es-CR). El tiempo se guarda en UTC y la interfaz
                                lo muestra en America/Costa_Rica.""")
                        .contact(new Contact().name("Equipo LogisticaPortuaria CR"))
                        .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(new Server().url("/api/v1").description("Servidor local")));
    }
}
