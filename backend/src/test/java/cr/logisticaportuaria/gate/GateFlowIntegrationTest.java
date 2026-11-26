package cr.logisticaportuaria.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.logisticaportuaria.gate.domain.ReadingKind;
import cr.logisticaportuaria.gate.domain.ReviewStatus;
import cr.logisticaportuaria.gate.domain.VisitState;
import cr.logisticaportuaria.gate.infrastructure.persistence.VisitLogRepository;
import cr.logisticaportuaria.support.AbstractIntegrationTest;
import cr.logisticaportuaria.yard.domain.Appointment;
import cr.logisticaportuaria.yard.domain.AppointmentType;
import cr.logisticaportuaria.yard.domain.Carrier;
import cr.logisticaportuaria.yard.domain.Container;
import cr.logisticaportuaria.yard.domain.ContainerStatus;
import cr.logisticaportuaria.yard.domain.ContainerType;
import cr.logisticaportuaria.yard.domain.Driver;
import cr.logisticaportuaria.yard.domain.Truck;
import cr.logisticaportuaria.yard.infrastructure.persistence.AppointmentRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.CarrierRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.ContainerRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.DriverRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.TruckRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test of the gate flow against a real PostgreSQL.
 *
 * <p>It verifies the ingestion of a reading of the edge, the creation of the
 * visit of the digital logbook, the idempotency by {@code eventId}, the state
 * machine (invalid transition → HTTP 409 with {@code ProblemDetail}) and the
 * manual review queue.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GateFlowIntegrationTest extends AbstractIntegrationTest {

    private static final UUID YARD_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID GATE_IN = UUID.fromString("33333333-3333-7333-8333-333333333331");
    private static final String PLATE = "TEST123";
    private static final String CONTAINER_CODE = "CSQU3054383";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private TruckRepository truckRepository;

    @Autowired
    private CarrierRepository carrierRepository;

    @Autowired
    private DriverRepository driverRepository;

    @Autowired
    private ContainerRepository containerRepository;

    @Autowired
    private VisitLogRepository visitLogRepository;

    @BeforeEach
    void setUp() {
        Carrier carrier = new Carrier("TEST-CARRIER", "Transportista de pruebas", "3-101-555999");
        carrierRepository.save(carrier);
        Driver driver = new Driver("CONDUCTOR DE PRUEBA", "LIC-TEST-1", "8000-0000");
        driverRepository.save(driver);
        Truck truck = new Truck(PLATE, carrier.getId(), driver.getId());
        truckRepository.save(truck);

        Appointment appointment = new Appointment(YARD_ID, GATE_IN, truck.getId(), AppointmentType.INGRESO,
                Instant.now().truncatedTo(ChronoUnit.SECONDS), 30, null, null);
        appointmentRepository.save(appointment);
    }

    @Test
    @DisplayName("Una lectura de matrícula con cita vigente se aprueba y abre la visita")
    void ingestsPlateReadingAndOpensTheVisit() throws Exception {
        JsonNode response = postReading(reading(ReadingKind.PLATE, PLATE, 0.98, null), 201);

        assertThat(response.get("result").asText()).isEqualTo("AUTO_APROBADO");
        assertThat(response.get("visitId").asText()).isNotBlank();
        assertThat(response.get("idempotent").asBoolean()).isFalse();

        String visitId = response.get("visitId").asText();
        JsonNode visit = getJson("/api/v1/visits/" + visitId);
        assertThat(visit.get("state").asText()).isEqualTo(VisitState.VALIDADO.name());
        assertThat(visit.get("plate").asText()).isEqualTo(PLATE);
        assertThat(visit.get("direction").asText()).isEqualTo("IN");
        assertThat(visit.get("timeline")).hasSize(2);

        // The update must reach the row: the entity returned by save() is the managed one.
        assertThat(visitLogRepository.findById(UUID.fromString(visitId)).orElseThrow().getState())
                .isEqualTo(VisitState.VALIDADO);
    }

    @Test
    @DisplayName("La misma lectura repetida se responde de forma idempotente (200 y no 201)")
    void ingestionIsIdempotentByEventId() throws Exception {
        Map<String, Object> body = reading(ReadingKind.PLATE, PLATE, 0.98, null);

        JsonNode first = postReading(body, 201);
        JsonNode second = postReading(body, 200);

        assertThat(second.get("idempotent").asBoolean()).isTrue();
        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(second.get("visitId").asText()).isEqualTo(first.get("visitId").asText());
        assertThat(second.get("result").asText()).isEqualTo(first.get("result").asText());
    }

    @Test
    @DisplayName("Una lectura con confianza baja exige revisión manual y encola la tarea")
    void lowConfidenceGoesToTheReviewQueue() throws Exception {
        JsonNode response = postReading(reading(ReadingKind.PLATE, PLATE, 0.40, null), 201);

        assertThat(response.get("result").asText()).isEqualTo("REQUIERE_REVISION");
        assertThat(response.get("reviewTaskId").asText()).isNotBlank();
        assertThat(response.get("reason").asText()).contains("confianza").contains("0.40");

        JsonNode tasks = getJson("/api/v1/review-tasks?yardId=" + YARD_ID + "&status=PENDIENTE");
        assertThat(tasks.isArray()).isTrue();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).get("id").asText()).isEqualTo(response.get("reviewTaskId").asText());
        assertThat(tasks.get(0).get("status").asText()).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("Una transición inválida de la visita responde 409 con ProblemDetail")
    void invalidTransitionAnswersConflict() throws Exception {
        JsonNode response = postReading(reading(ReadingKind.PLATE, PLATE, 0.98, null), 201);
        String visitId = response.get("visitId").asText();

        MvcResult result = mockMvc.perform(post("/api/v1/visits/{id}/transitions", visitId)
                        .with(user("operador").roles("OPERADOR_PUERTA"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetState\":\"SALIDA_PUERTA\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.detail").exists())
                .andReturn();

        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.get("type").asText()).contains("conflict");
        assertThat(problem.get("detail").asText()).contains("VALIDADO").contains("SALIDA_PUERTA");
    }

    @Test
    @DisplayName("La secuencia de estados de la visita avanza hasta SALIDA_PUERTA")
    void visitAdvancesThroughTheStates() throws Exception {
        JsonNode response = postReading(reading(ReadingKind.PLATE, PLATE, 0.98, null), 201);
        String visitId = response.get("visitId").asText();

        for (String target : new String[] {"ASIGNADO", "EN_PROCESO", "SALIDA_PUERTA"}) {
            JsonNode visit = postTransition(visitId, target);
            assertThat(visit.get("state").asText()).isEqualTo(target);
        }

        JsonNode visit = getJson("/api/v1/visits/" + visitId);
        assertThat(visit.get("exitedAt").isNull()).isFalse();
        assertThat(visit.get("yardSeconds").isNull()).isFalse();
        assertThat(visit.get("timeline")).hasSize(5);
    }

    @Test
    @DisplayName("La visita cancelada no admite más transiciones")
    void cancelledVisitIsFinal() throws Exception {
        JsonNode response = postReading(reading(ReadingKind.PLATE, PLATE, 0.98, null), 201);
        String visitId = response.get("visitId").asText();

        assertThat(postTransition(visitId, "CANCELADO").get("state").asText()).isEqualTo("CANCELADO");

        mockMvc.perform(post("/api/v1/visits/{id}/transitions", visitId)
                        .with(user("operador").roles("OPERADOR_PUERTA"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetState\":\"VALIDADO\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Una revisión manual aprobada deja la visita validada")
    void manualReviewApprovesTheVisit() throws Exception {
        JsonNode response = postReading(reading(ReadingKind.CONTAINER, "CSQU3054380", 0.95, false), 201);
        assertThat(response.get("result").asText()).isEqualTo("REQUIERE_REVISION");
        String taskId = response.get("reviewTaskId").asText();

        JsonNode task = objectMapper.readTree(mockMvc.perform(post("/api/v1/review-tasks/{id}/resolve", taskId)
                        .with(user("operador").roles("OPERADOR_PUERTA"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"CORREGIDO\",\"correctedText\":\"CSQU3054383\","
                                + "\"reason\":\"Se corrigió el dígito de control.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ReviewStatus.CORREGIDO.name()))
                .andReturn().getResponse().getContentAsString());

        assertThat(task.get("resolvedText").asText()).isEqualTo("CSQU3054383");
        assertThat(task.get("resolvedBy").asText()).isNotBlank();

        JsonNode visit = getJson("/api/v1/visits/" + response.get("visitId").asText());
        assertThat(visit.get("state").asText()).isEqualTo(VisitState.VALIDADO.name());
        assertThat(visit.get("containerCode").asText()).isEqualTo("CSQU3054383");
    }

    @Test
    @DisplayName("Una tarea de revisión ya resuelta no se puede resolver dos veces")
    void resolvedTaskCannotBeResolvedAgain() throws Exception {
        JsonNode response = postReading(reading(ReadingKind.PLATE, PLATE, 0.30, null), 201);
        String taskId = response.get("reviewTaskId").asText();

        resolveTask(taskId, "APROBADO");
        mockMvc.perform(post("/api/v1/review-tasks/{id}/resolve", taskId)
                        .with(user("operador").roles("OPERADOR_PUERTA"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"APROBADO\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("El listado de contenedores y el mapa de slots del patio funciona")
    void listsContainersAndSlots() throws Exception {
        JsonNode containers = getJson("/api/v1/containers?size=5");
        assertThat(containers.get("content")).hasSize(5);
        assertThat(containers.get("totalElements").asLong()).isEqualTo(110);

        JsonNode slots = getJson("/api/v1/yard/slots?yardId=" + YARD_ID);
        assertThat(slots.isArray()).isTrue();
        assertThat(slots).hasSize(630);
    }

    @Test
    @DisplayName("Un contenedor en GATE_HOLD se rechaza en la puerta")
    void rejectsContainerUnderHold() throws Exception {
        Container container = new Container(CONTAINER_CODE, 20, ContainerType.dry, ContainerStatus.GATE_HOLD,
                "CLI-TEST", Instant.now().plus(1, ChronoUnit.DAYS), Instant.now().plus(2, ChronoUnit.DAYS),
                false);
        containerRepository.save(container);

        JsonNode response = postReading(reading(ReadingKind.CONTAINER, CONTAINER_CODE, 0.99, true), 201);

        assertThat(response.get("result").asText()).isEqualTo("RECHAZADO");
        assertThat(response.get("reason").asText()).contains("GATE_HOLD");
    }

    private void resolveTask(String taskId, String resolution) throws Exception {
        mockMvc.perform(post("/api/v1/review-tasks/{id}/resolve", taskId)
                        .with(user("operador").roles("OPERADOR_PUERTA"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"" + resolution + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode postReading(Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/gate-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postTransition(String visitId, String targetState) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/visits/{id}/transitions", visitId)
                        .with(user("operador").roles("OPERADOR_PUERTA"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetState\":\"" + targetState + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .with(user("auditor").roles("AUDITOR")))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> reading(ReadingKind kind, String text, double confidence, Boolean isoValid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", UUID.randomUUID().toString());
        body.put("yardId", YARD_ID.toString());
        body.put("gateId", GATE_IN.toString());
        body.put("timestamp", Instant.now().toString());
        body.put("kind", kind.name());
        body.put("rawText", text);
        body.put("normalizedText", text);
        body.put("confidence", confidence);
        if (isoValid != null) {
            body.put("isoValid", isoValid);
        }
        body.put("corrected", false);
        return body;
    }
}
