package cr.logisticaportuaria.optimizer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.logisticaportuaria.support.AbstractIntegrationTest;
import cr.logisticaportuaria.yard.domain.Container;
import cr.logisticaportuaria.yard.domain.ContainerStatus;
import cr.logisticaportuaria.yard.domain.ContainerType;
import cr.logisticaportuaria.yard.infrastructure.persistence.ContainerRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration test of the three optimizer endpoints against a real PostgreSQL.
 *
 * <p>The class is deliberately <em>not</em> {@code @Transactional}: the asynchronous plan
 * reads the containers in a worker thread, so the inserted rows have to be committed. The
 * created containers are removed in {@link #cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OptimizerIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> CODES = List.of("TSTU1234568", "TSTU1234573", "TSTU1234589");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContainerRepository containerRepository;

    @AfterEach
    void cleanUp() {
        for (String code : CODES) {
            containerRepository.findByCode(code).ifPresent(containerRepository::delete);
        }
    }

    @Test
    @DisplayName("POST /optimizer/recommendations devuelve el Top-3 con explicación en español")
    void recommendationsReturnTopThreeWithExplanation() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("containerCode", "TSTU1234568");
        body.put("sizeFt", 20);
        body.put("containerType", "dry");
        body.put("weightKg", 9_000);
        body.put("etd", Instant.now().plus(2, ChronoUnit.DAYS).toString());
        body.put("clientCode", "ACME");

        mockMvc.perform(post("/api/v1/optimizer/recommendations")
                        .with(user("operador").roles("OPERADOR_PUERTA"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("HEURISTIC"))
                .andExpect(jsonPath("$.recommendations.length()").value(3))
                .andExpect(jsonPath("$.recommendations[0].rank").value(1))
                .andExpect(jsonPath("$.recommendations[0].slot.id").exists())
                .andExpect(jsonPath("$.recommendations[0].slot.sizeFt").value(20))
                .andExpect(jsonPath("$.recommendations[0].slot.distanceToGateMeters").exists())
                .andExpect(jsonPath("$.recommendations[0].explanation").value(org.hamcrest.Matchers.containsString("Bloque")));
    }

    @Test
    @DisplayName("POST /optimizer/recommendations responde 422 cuando ningún slot acepta el contenedor")
    void recommendationsAnswer422WhenThereIsNoFeasibleSlot() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sizeFt", 20);
        body.put("containerType", "hazmat");
        body.put("requiresPower", true);

        mockMvc.perform(post("/api/v1/optimizer/recommendations")
                        .with(user("supervisor").roles("SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.type").value("urn:logisticaportuaria:error:business-rule"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("slot")));
    }

    @Test
    @DisplayName("Un tamaño que no es 20 ni 40 responde 400 y no llega al optimizador")
    void invalidSizeIsRejected() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sizeFt", 25);
        body.put("containerType", "dry");

        mockMvc.perform(post("/api/v1/optimizer/recommendations")
                        .with(user("operador").roles("OPERADOR_PUERTA"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:logisticaportuaria:error:validation"));
    }

    @Test
    @DisplayName("El plan asíncrono arranca con 202 y termina en DONE con movimientos")
    void asynchronousPlanFinishes() throws Exception {
        Instant etd = Instant.now().plus(5, ChronoUnit.DAYS);
        for (int i = 0; i < CODES.size(); i++) {
            containerRepository.save(new Container(CODES.get(i), 20, ContainerType.dry, ContainerStatus.IN_TRANSIT,
                    "CLI-1", null, etd.plus(i, ChronoUnit.DAYS), false));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("containerCodes", CODES);
        body.put("strategy", "HEURISTIC");
        body.put("timeLimitSeconds", 15);

        MvcResult created = mockMvc.perform(post("/api/v1/optimizer/plans")
                        .with(user("supervisor").roles("SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.planId").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String planId = objectMapper.readTree(created.getResponse().getContentAsString()).path("planId").asText();

        JsonNode plan = awaitTerminalPlan(planId);

        assertThat(plan.path("status").asText()).isEqualTo("DONE");
        assertThat(plan.path("strategy").asText()).isEqualTo("HEURISTIC");
        assertThat(plan.path("moves")).hasSize(CODES.size());
        assertThat(plan.path("moves").get(0).path("action").asText()).isEqualTo("PLACE");
        assertThat(plan.path("moves").get(0).path("toSlotId").asText()).isNotBlank();
        assertThat(plan.path("metrics").path("totalSlots").asInt()).isEqualTo(630);
        assertThat(plan.path("message").asText()).contains("ubicado");
    }

    @Test
    @DisplayName("Un contenedor que ya está en el patio no se vuelve a ubicar")
    void containersAlreadyInTheYardAreSkipped() throws Exception {
        String code = findSeededContainerCode();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("containerCodes", List.of(code));
        body.put("strategy", "HEURISTIC");

        MvcResult created = mockMvc.perform(post("/api/v1/optimizer/plans")
                        .with(user("supervisor").roles("SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andReturn();
        String planId = objectMapper.readTree(created.getResponse().getContentAsString()).path("planId").asText();

        JsonNode plan = awaitTerminalPlan(planId);

        assertThat(plan.path("status").asText()).isEqualTo("DONE");
        assertThat(plan.path("moves")).isEmpty();
        assertThat(plan.path("alreadyPlaced").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Un código inexistente responde 422")
    void unknownContainerIsRejected() throws Exception {
        Map<String, Object> body = Map.of("containerCodes", List.of("XXXU0000000"), "strategy", "HEURISTIC");

        mockMvc.perform(post("/api/v1/optimizer/plans")
                        .with(user("supervisor").roles("SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("XXXU0000000")));
    }

    @Test
    @DisplayName("GET /optimizer/metrics publica la ocupación y los restows esperados del patio")
    void metricsDescribeTheYard() throws Exception {
        mockMvc.perform(get("/api/v1/optimizer/metrics").with(user("auditor").roles("AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSlots").value(630))
                .andExpect(jsonPath("$.occupiedSlots").value(110))
                .andExpect(jsonPath("$.occupancy").isNumber())
                .andExpect(jsonPath("$.expectedRestows").isNumber())
                .andExpect(jsonPath("$.averageDistanceMeters").isNumber());
    }

    @Test
    @DisplayName("Sin token el optimizador responde 401")
    void anonymousRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/optimizer/metrics"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode awaitTerminalPlan(String planId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode plan = null;
        while (System.nanoTime() < deadline) {
            MvcResult result = mockMvc.perform(get("/api/v1/optimizer/plans/{planId}", planId)
                            .with(user("supervisor").roles("SUPERVISOR")))
                    .andExpect(status().isOk())
                    .andReturn();
            plan = objectMapper.readTree(result.getResponse().getContentAsString());
            if (List.of("DONE", "FAILED").contains(plan.path("status").asText())) {
                return plan;
            }
            Thread.sleep(100);
        }
        fail("El plan %s no terminó en 30 s: %s".formatted(planId, plan));
        return plan;
    }

    private String findSeededContainerCode() {
        return containerRepository.findAll().stream()
                .map(Container::getCode)
                .findFirst()
                .orElseThrow();
    }
}
