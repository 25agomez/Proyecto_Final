package cr.logisticaportuaria.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.logisticaportuaria.fleet.application.AlertStateStore;
import cr.logisticaportuaria.fleet.application.FleetAlertBuffer;
import cr.logisticaportuaria.fleet.application.FleetMetrics;
import cr.logisticaportuaria.fleet.application.FleetStateCache;
import cr.logisticaportuaria.fleet.application.FleetTelemetryService;
import cr.logisticaportuaria.fleet.application.GeofenceCache;
import cr.logisticaportuaria.fleet.application.IngestResult;
import cr.logisticaportuaria.fleet.application.VehicleDirectory;
import cr.logisticaportuaria.fleet.domain.AlertType;
import cr.logisticaportuaria.fleet.domain.FleetAlert;
import cr.logisticaportuaria.fleet.domain.YardFrame;
import cr.logisticaportuaria.fleet.infrastructure.mqtt.FleetTelemetryMqttSubscriber;
import cr.logisticaportuaria.fleet.infrastructure.websocket.FleetBroadcaster;
import cr.logisticaportuaria.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test of the fleet module against a real PostgreSQL.
 *
 * <p>No broker is used: the subscriber exposes
 * {@link FleetTelemetryMqttSubscriber#handleTopic(String, String)}, which is the same path the
 * MQTT callback uses (MEMORY.md §9 B-001). The tests reset the two seeded vehicles to a known
 * state and remove their positions afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FleetIntegrationTest extends AbstractIntegrationTest {

    private static final UUID YARD = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID V1 = UUID.fromString("70000000-0000-7000-8000-000000000001");
    private static final UUID V2 = UUID.fromString("70000000-0000-7000-8000-000000000002");
    private static final UUID UNKNOWN = UUID.fromString("70000000-0000-7000-8000-00000000ffff");
    private static final Instant BASE = Instant.parse("2026-10-22T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FleetTelemetryMqttSubscriber subscriber;

    @Autowired
    private FleetStateCache stateCache;

    @Autowired
    private FleetAlertBuffer alertBuffer;

    @Autowired
    private FleetMetrics metrics;

    @Autowired
    private FleetBroadcaster broadcaster;

    @Autowired
    private GeofenceCache geofenceCache;

    @Autowired
    private VehicleDirectory vehicleDirectory;

    @Autowired
    private AlertStateStore alertStateStore;

    @Autowired
    private FleetTelemetryService telemetryService;

    @BeforeEach
    void resetFleet() {
        jdbcTemplate.update("DELETE FROM vehicle_position WHERE vehicle_id IN (?, ?)", V1, V2);
        jdbcTemplate.update("""
                UPDATE vehicle_last_state
                SET at = '2026-01-01T00:00:00Z', x = 10, y = 10, speed_kmh = 0, status = 'LIBRE'
                WHERE vehicle_id IN (?, ?)
                """, V1, V2);
        jdbcTemplate.update("UPDATE vehicle SET status = 'LIBRE' WHERE id IN (?, ?)", V1, V2);
        jdbcTemplate.update("DELETE FROM work_order WHERE container_code = 'CSQU3054383'");
        stateCache.clear();
        alertBuffer.clear();
        alertStateStore.forget(V1);
        alertStateStore.forget(V2);
        geofenceCache.invalidate();
        vehicleDirectory.invalidate();
        broadcaster.flush();
    }

    @AfterEach
    void cleanUp() {
        broadcaster.flush();
    }

    @Test
    @DisplayName("la telemetria valida se persiste y se puede consultar por REST")
    void acceptedTelemetryIsPersistedAndQueryable() throws Exception {
        assertThat(ingest(V1, BASE, 100, 20, 8, 90.0, "EN_MOVIMIENTO", null))
                .isEqualTo(IngestResult.ACCEPTED);

        Long positions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vehicle_position WHERE vehicle_id = ?", Long.class, V1);
        assertThat(positions).isEqualTo(1);
        Double x = jdbcTemplate.queryForObject(
                "SELECT x FROM vehicle_last_state WHERE vehicle_id = ?", Double.class, V1);
        assertThat(x).isCloseTo(100.0, org.assertj.core.api.Assertions.within(0.01));

        mockMvc.perform(get("/api/v1/fleet/vehicles")
                        .with(user("operador").roles("OPERADOR_PUERTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + V1 + "')].code").value("VEH-01"));

        mockMvc.perform(get("/api/v1/fleet/vehicles/{id}/track", V1)
                        .param("from", BASE.minusSeconds(60).toString())
                        .param("to", BASE.plusSeconds(60).toString())
                        .with(user("operador").roles("OPERADOR_PUERTA")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("application/geo+json")))
                .andExpect(jsonPath("$.type").value("LineString"))
                .andExpect(jsonPath("$.coordinates.length()").value(1))
                .andExpect(jsonPath("$.coordinates[0].length()").value(2));

        mockMvc.perform(get("/api/v1/fleet/kpis")
                        .with(user("supervisor").roles("SUPERVISOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counters.persisted").isNumber())
                .andExpect(jsonPath("$.yardId").value(YARD.toString()));
    }

    @Test
    @DisplayName("duplicados, desorden y saltos imposibles no se persisten")
    void duplicatesOutOfOrderAndJumpsAreRejected() {
        assertThat(ingest(V1, BASE, 100, 20, 8, 90.0, "EN_MOVIMIENTO", null))
                .isEqualTo(IngestResult.ACCEPTED);
        assertThat(ingest(V1, BASE, 100, 20, 8, 90.0, "EN_MOVIMIENTO", null))
                .isEqualTo(IngestResult.DUPLICATE);
        assertThat(ingest(V1, BASE.minusSeconds(30), 90, 20, 8, 90.0, "EN_MOVIMIENTO", null))
                .isEqualTo(IngestResult.OUT_OF_ORDER);
        assertThat(ingest(V1, BASE.plusSeconds(1), 400, 100, 8, 90.0, "EN_MOVIMIENTO", null))
                .isEqualTo(IngestResult.IMPOSSIBLE_JUMP);

        Long positions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vehicle_position WHERE vehicle_id = ?", Long.class, V1);
        assertThat(positions).isEqualTo(1);
        assertThat(alertBuffer.since(BASE.minusSeconds(60)))
                .extracting(FleetAlert::type).contains(AlertType.SALTO_GPS);
    }

    @Test
    @DisplayName("mensajes invalidos y vehiculos desconocidos se descartan")
    void invalidAndUnknownAreRejected() {
        assertThat(subscriber.handleTopic("yard/" + YARD + "/fleet/" + V1 + "/telemetry",
                "{not-json")).isEqualTo(IngestResult.INVALID);
        assertThat(subscriber.handleTopic("yard/" + YARD + "/fleet/" + V1 + "/telemetry",
                payload(V1, BASE, 10, 10, 5, 80.0, "VOLANDO", null)))
                .isEqualTo(IngestResult.INVALID);
        assertThat(ingest(UNKNOWN, BASE, 10, 10, 5, 80.0, "LIBRE", null))
                .isEqualTo(IngestResult.UNKNOWN_VEHICLE);
        assertThat(subscriber.handleTopic("yard/" + YARD + "/fleet/" + V1 + "/other",
                payload(V1, BASE, 10, 10, 5, 80.0, "LIBRE", null)))
                .isEqualTo(IngestResult.INVALID);
    }

    @Test
    @DisplayName("las geocercas, la velocidad, la bateria y la averia generan alertas")
    void alertRulesOverTheSeedGeofences() {
        assertThat(ingest(V1, BASE, 10, 10, 5, 90.0, "EN_MOVIMIENTO", null))
                .isEqualTo(IngestResult.ACCEPTED);
        // (230, 80) is inside the forbidden zone of the seed.
        assertThat(ingest(V1, BASE.plusSeconds(120), 230, 80, 30, 85.0, "EN_MOVIMIENTO", null))
                .isEqualTo(IngestResult.ACCEPTED);
        assertThat(ingest(V1, BASE.plusSeconds(180), 230, 80, 0, 12.0, "LIBRE", null))
                .isEqualTo(IngestResult.ACCEPTED);
        assertThat(ingest(V1, BASE.plusSeconds(240), 230, 80, 0, 12.0, "AVERIA", null))
                .isEqualTo(IngestResult.ACCEPTED);

        List<AlertType> types = alertBuffer.since(BASE).stream().map(FleetAlert::type).toList();
        assertThat(types).contains(AlertType.ZONA_PROHIBIDA, AlertType.EXCESO_VELOCIDAD,
                AlertType.BATERIA_BAJA, AlertType.AVERIA, AlertType.ENTRADA_ZONA);
    }

    @Test
    @DisplayName("el silencio prolongado genera SIN_SENAL una sola vez por episodio")
    void silenceAlert() {
        assertThat(ingest(V1, BASE, 10, 10, 5, 90.0, "EN_MOVIMIENTO", null))
                .isEqualTo(IngestResult.ACCEPTED);
        assertThat(alertBuffer.since(BASE)).isEmpty();

        // 120 s later, no telemetry arrived: one SIN_SENAL, not one per check.
        assertThat(telemetryService.checkSilence(BASE.plusSeconds(120))).isEqualTo(1);
        assertThat(telemetryService.checkSilence(BASE.plusSeconds(130))).isZero();
        assertThat(alertBuffer.since(BASE))
                .extracting(FleetAlert::type).containsExactly(AlertType.SIN_SENAL);
    }

    @Test
    @DisplayName("las geocercas del seed se exponen al tablero")
    void geofencesAreExposed() throws Exception {
        mockMvc.perform(get("/api/v1/fleet/geofences")
                        .with(user("auditor").roles("AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].coordinates").isArray());
    }

    @Test
    @DisplayName("flujo completo de una orden de trabajo")
    void workOrderLifecycle() throws Exception {
        String created = mockMvc.perform(post("/api/v1/fleet/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"yardId":"%s","containerCode":"CSQU3054383","orderType":"REUBICACION","priority":1}
                                """.formatted(YARD))
                        .with(user("supervisor").roles("SUPERVISOR")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDIENTE"))
                .andReturn().getResponse().getContentAsString();
        String orderId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        mockMvc.perform(post("/api/v1/fleet/work-orders/{id}/start", orderId)
                        .with(user("supervisor").roles("SUPERVISOR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(post("/api/v1/fleet/work-orders/{id}/assign", orderId)
                        .with(user("supervisor").roles("SUPERVISOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASIGNADA"))
                .andExpect(jsonPath("$.vehicleCode").isNotEmpty());

        mockMvc.perform(post("/api/v1/fleet/work-orders/{id}/start", orderId)
                        .with(user("supervisor").roles("SUPERVISOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EN_CURSO"));

        mockMvc.perform(post("/api/v1/fleet/work-orders/{id}/complete", orderId)
                        .with(user("supervisor").roles("SUPERVISOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETADA"));
    }

    @Test
    @DisplayName("sin token la API de flota responde 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/fleet/vehicles"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("traza de un contenedor: 404 si no existe y 200 con el timeline")
    void containerTrace() throws Exception {
        mockMvc.perform(get("/api/v1/containers/{code}/trace", "ZZZU1234567")
                        .with(user("operador").roles("OPERADOR_PUERTA")))
                .andExpect(status().isNotFound());

        String code = jdbcTemplate.queryForObject("""
                SELECT c.code FROM container c JOIN container_position cp ON cp.container_id = c.id
                ORDER BY c.code LIMIT 1
                """, String.class);
        mockMvc.perform(get("/api/v1/containers/{code}/trace", code)
                        .with(user("operador").roles("OPERADOR_PUERTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.timeline").isArray())
                .andExpect(jsonPath("$.timeline[0].type").isNotEmpty());
    }

    private IngestResult ingest(UUID vehicleId, Instant ts, double x, double y, double speed,
            Double battery, String status, String container) {
        return subscriber.handleTopic("yard/" + YARD + "/fleet/" + vehicleId + "/telemetry",
                payload(vehicleId, ts, x, y, speed, battery, status, container));
    }

    private String payload(UUID vehicleId, Instant ts, double x, double y, double speed,
            Double battery, String status, String container) {
        return String.format(Locale.ROOT,
                "{\"vehicleId\":\"%s\",\"ts\":\"%s\",\"lat\":%.8f,\"lon\":%.8f,"
                        + "\"speedKmh\":%.1f,\"headingDeg\":90.0,\"batteryPct\":%s,"
                        + "\"status\":\"%s\"%s}",
                vehicleId, ts, YardFrame.latOf(y), YardFrame.lonOf(x), speed,
                battery == null ? "null" : battery,
                status,
                container == null ? "" : ",\"containerCode\":\"" + container + "\"");
    }
}
