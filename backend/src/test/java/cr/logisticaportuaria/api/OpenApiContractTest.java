package cr.logisticaportuaria.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.logisticaportuaria.support.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.yaml.snakeyaml.Yaml;

/**
 * Contract test against {@code docs/api/openapi.yaml}.
 *
 * <p>The contract is the property of the architect: this test verifies that
 * every operation it declares is served by the backend. The paths of the
 * modules that arrive in later prompts are declared in the contract but are
 * not implemented yet: they are reported as pending instead of failing the
 * build.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest extends AbstractIntegrationTest {

    /** Paths of the contract that belong to the prompts that are still pending. */
    private static final List<String> DEFERRED_PREFIXES = List.of("/congestion");

    /** Base path declared in {@code servers} of the contract. */
    private static final String BASE_PATH = "/api/v1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Toda operacion del contrato está servida por el backend (salvo las diferidas)")
    void generatedDocumentMatchesTheContract() throws Exception {
        JsonNode contract = readContract();
        JsonNode generated = readGeneratedDocument();

        Set<String> contractOperations = operations(contract);
        Set<String> generatedOperations = operations(generated);

        List<String> missing = new ArrayList<>();
        List<String> deferred = new ArrayList<>();
        for (String operation : contractOperations) {
            if (generatedOperations.contains(operation)) {
                continue;
            }
            String path = operation.substring(operation.indexOf(' ') + 1);
            if (DEFERRED_PREFIXES.stream().anyMatch(path::startsWith)) {
                deferred.add(operation);
            } else {
                missing.add(operation);
            }
        }

        assertThat(contractOperations).isNotEmpty();
        assertThat(missing)
                .as("Operaciones del contrato que el backend no sirve")
                .isEmpty();
        assertThat(deferred)
                .as("Operaciones del contrato pendientes")
                .isEmpty();
    }

    @Test
    @DisplayName("El contrato declara ProblemDetail (RFC 7807) y el backend lo usa en los errores")
    void errorResponsesUseProblemDetail() throws Exception {
        JsonNode contract = readContract();
        JsonNode schemas = contract.path("components").path("schemas");
        JsonNode problem = schemas.path("ProblemDetail");

        assertThat(problem.isMissingNode()).isFalse();
        assertThat(problem.path("properties").path("detail").isMissingNode()).isFalse();
        assertThat(problem.path("properties").path("status").isMissingNode()).isFalse();

        // Every documented error response refers to the same ProblemDetail schema.
        JsonNode responses = contract.path("components").path("responses");
        Iterator<String> names = responses.fieldNames();
        int checked = 0;
        while (names.hasNext()) {
            JsonNode response = responses.path(names.next());
            JsonNode schema = response.path("content").path("application/problem+json").path("schema");
            assertThat(schema.path("$ref").asText()).endsWith("/ProblemDetail");
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(4);

        // And the running backend answers a 404 with that shape.
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/visits/{id}", UUID.randomUUID())
                        .with(SecurityMockMvcRequestPostProcessors.user("auditor").roles("AUDITOR")))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(404))
                .andExpect(MockMvcResultMatchers.jsonPath("$.type").exists())
                .andExpect(MockMvcResultMatchers.jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("Los caminos del contrato de la puerta y el patio están publicados en /v3/api-docs")
    void gatePathsArePublished() throws Exception {
        JsonNode generated = readGeneratedDocument();

        Set<String> paths = new LinkedHashSet<>();
        generated.path("paths").fieldNames().forEachRemaining(path -> paths.add(normalize(path)));

        assertThat(paths).contains(
                "/gate-events",
                "/visits",
                "/visits/{id}",
                "/visits/{id}/transitions",
                "/review-tasks",
                "/review-tasks/{id}/resolve",
                "/containers",
                "/containers/{code}",
                "/yard/slots",
                "/optimizer/recommendations",
                "/optimizer/plans",
                "/fleet/vehicles",
                "/fleet/vehicles/{id}/track",
                "/fleet/kpis",
                "/fleet/geofences",
                "/fleet/work-orders",
                "/fleet/work-orders/{id}/assign",
                "/fleet/work-orders/{id}/start",
                "/fleet/work-orders/{id}/complete",
                "/fleet/work-orders/{id}/cancel",
                "/fleet/work-orders/assign-next",
                "/containers/{code}/trace");
    }

    @Test
    @DisplayName("El documento generado declara la base /api/v1 igual que el contrato")
    void basePathIsDocumented() throws Exception {
        JsonNode generated = readGeneratedDocument();

        String server = generated.path("servers").path(0).path("url").asText();
        assertThat(server).isEqualTo(BASE_PATH);
    }

    @Test
    @DisplayName("El backend tolera filtros vacios en los listados (parámetros nulos)")
    void emptyFiltersAreAccepted() throws Exception {
        for (String path : List.of("/api/v1/visits", "/api/v1/containers", "/api/v1/review-tasks",
                "/api/v1/yard/slots")) {
            mockMvc.perform(MockMvcRequestBuilders.get(path)
                            .with(SecurityMockMvcRequestPostProcessors.user("auditor").roles("AUDITOR")))
                    .andExpect(MockMvcResultMatchers.status().isOk());
        }
    }

    private JsonNode readContract() throws IOException {
        Path path = Path.of("..", "docs", "api", "openapi.yaml");
        assertThat(Files.exists(path))
                .as("No se encontró el contrato %s", path.toAbsolutePath())
                .isTrue();
        Map<String, Object> document = new Yaml().load(Files.readString(path));
        return objectMapper.valueToTree(document);
    }

    private JsonNode readGeneratedDocument() throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    /**
     * Removes the base path so the paths of the generated document can be
     * compared with the ones of the contract (which are relative to
     * {@code /api/v1}).
     */
    private static String normalize(String path) {
        return path.startsWith(BASE_PATH) ? path.substring(BASE_PATH.length()) : path;
    }

    private Set<String> operations(JsonNode document) {
        Set<String> operations = new LinkedHashSet<>();
        JsonNode paths = document.path("paths");
        Iterator<Map.Entry<String, JsonNode>> items = paths.fields();
        while (items.hasNext()) {
            Map.Entry<String, JsonNode> entry = items.next();
            String path = normalize(entry.getKey());
            Iterator<String> methods = entry.getValue().fieldNames();
            while (methods.hasNext()) {
                String method = methods.next();
                if (List.of("get", "post", "put", "patch", "delete").contains(method)) {
                    operations.add(method.toUpperCase(Locale.ROOT) + " " + path);
                }
            }
        }
        return operations;
    }
}
