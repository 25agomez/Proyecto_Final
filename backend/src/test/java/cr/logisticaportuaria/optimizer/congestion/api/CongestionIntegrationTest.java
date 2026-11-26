package cr.logisticaportuaria.optimizer.congestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.logisticaportuaria.optimizer.congestion.application.CongestionService;
import cr.logisticaportuaria.optimizer.congestion.application.dto.CongestionForecastResponse;
import cr.logisticaportuaria.optimizer.congestion.domain.CostaRicaCalendar;
import cr.logisticaportuaria.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test of the congestion API against a real PostgreSQL.
 *
 * <p>The seed of {@code V2__seed_demo.sql} has no {@code visit_log} rows, so the gate G1 is
 * filled with a deterministic synthetic history when a test needs a trained model. Every test
 * removes its rows afterwards, because the trained model is cached per gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CongestionIntegrationTest extends AbstractIntegrationTest {

    private static final UUID YARD = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID G1 = UUID.fromString("33333333-3333-7333-8333-333333333331");
    private static final UUID G2 = UUID.fromString("33333333-3333-7333-8333-333333333332");
    private static final String INSERT = """
            INSERT INTO visit_log (yard_id, gate_id, direction, state, result, entered_at, validated_at,
                                   assigned_at, exited_at, wait_seconds, service_seconds)
            VALUES (?, ?, 'IN', 'SALIDA_PUERTA', 'AUTO_APROBADO', ?, ?, ?, ?, ?, ?)
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CongestionService service;

    @BeforeEach
    void clearHistory() {
        deleteVisits();
    }

    @AfterEach
    void cleanUp() {
        deleteVisits();
    }

    @Test
    @DisplayName("sin token el pronóstico responde 401")
    void forecastRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/congestion/forecast").param("gateId", G1.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("una puerta sin histórico degrada al modelo de colas con confianza BAJA")
    void gateWithoutHistoryDegradesToTheQueueingModel() throws Exception {
        mockMvc.perform(get("/api/v1/congestion/forecast")
                        .param("gateId", G2.toString())
                        .param("horizon", "15")
                        .with(user("operador").roles("OPERADOR_PUERTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateCode").value("G2"))
                .andExpect(jsonPath("$.horizonMin").value(15))
                .andExpect(jsonPath("$.model").value("COLAS"))
                .andExpect(jsonPath("$.confidence").value("BAJA"))
                .andExpect(jsonPath("$.quality.samples").value(0))
                .andExpect(jsonPath("$.quality.active").value("COLAS"))
                .andExpect(jsonPath("$.drivers.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.recommendations").isNotEmpty())
                .andExpect(jsonPath("$.riskLevel").exists())
                .andExpect(jsonPath("$.p10").isNumber())
                .andExpect(jsonPath("$.p90").isNumber())
                .andExpect(jsonPath("$.expectedWaitMin").isNumber());
    }

    @Test
    @DisplayName("un horizonte fuera de 15/30/60 responde 422 con ProblemDetail")
    void invalidHorizonIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/congestion/forecast")
                        .param("gateId", G1.toString())
                        .param("horizon", "45")
                        .with(user("supervisor").roles("SUPERVISOR")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:logisticaportuaria:error:business-rule"))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("una puerta inexistente responde 404")
    void unknownGateIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/congestion/forecast")
                        .param("gateId", UUID.randomUUID().toString())
                        .with(user("auditor").roles("AUDITOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("con histórico el modelo se entrena y publica su calidad medida")
    void forecastWithHistoryTrainsTheModel() throws Exception {
        seedVisits(15, 9010L);

        for (int horizon : List.of(15, 30, 60)) {
            mockMvc.perform(get("/api/v1/congestion/forecast")
                            .param("gateId", G1.toString())
                            .param("horizon", String.valueOf(horizon))
                            .with(user("supervisor").roles("SUPERVISOR")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.horizonMin").value(horizon))
                    .andExpect(jsonPath("$.quality.samples").value(org.hamcrest.Matchers.greaterThan(60)))
                    .andExpect(jsonPath("$.confidence").value(org.hamcrest.Matchers.not("BAJA")))
                    .andExpect(jsonPath("$.quality.queueMae").isNumber())
                    .andExpect(jsonPath("$.quality.hybridMae").isNumber())
                    .andExpect(jsonPath("$.quality.naiveMae").isNumber())
                    .andExpect(jsonPath("$.drivers.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
        }
    }

    @Test
    @DisplayName("el mapa de calor devuelve la matriz 7x24 y sus celdas no vacías")
    void heatmapReturnsTheMatrix() throws Exception {
        seedVisits(10, 9020L);
        Instant to = Instant.now();
        Instant from = to.minus(10, ChronoUnit.DAYS);

        mockMvc.perform(get("/api/v1/congestion/heatmap")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("gateId", G1.toString())
                        .with(user("operador").roles("OPERADOR_PUERTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrix.length()").value(7))
                .andExpect(jsonPath("$.matrix[0].length()").value(24))
                .andExpect(jsonPath("$.cells").isNotEmpty())
                .andExpect(jsonPath("$.cells[0].dayOfWeek").isNumber())
                .andExpect(jsonPath("$.cells[0].hour").isNumber())
                .andExpect(jsonPath("$.cells[0].samples").isNumber());
    }

    @Test
    @DisplayName("el recálculo persiste los tres horizontes de cada puerta activa")
    void refreshPersistsTheThreeHorizons() {
        seedVisits(15, 9030L);
        Instant now = Instant.now();

        List<CongestionForecastResponse> responses = service.refreshAll(now);

        assertThat(responses).hasSize(6);
        assertThat(responses).extracting(CongestionForecastResponse::horizonMin)
                .containsOnly(15, 30, 60);
        assertThat(service.latestPersisted(G1, 15)).isPresent();
        assertThat(service.latestPersisted(G1, 60)).isPresent();
        assertThat(service.latestPersisted(G2, 30)).isPresent();
        assertThat(service.latestPersisted(G1, 15).orElseThrow().getRiskLevel()).isNotNull();
        assertThat(service.latestPersisted(G1, 15).orElseThrow().getDrivers()).isNotEmpty();
    }

    private void deleteVisits() {
        jdbcTemplate.update("DELETE FROM congestion_forecast WHERE gate_id IN (?, ?)", G1, G2);
        jdbcTemplate.update("DELETE FROM visit_log WHERE gate_id IN (?, ?)", G1, G2);
        service.invalidateCache();
    }

    /**
     * Inserts a deterministic synthetic history: a Poisson arrival process with the daily
     * profile of the simulator and a wait that grows with the occupancy of the bucket.
     */
    private void seedVisits(int days, long seed) {
        Random random = new Random(seed);
        Instant end = Instant.now().minus(20, ChronoUnit.MINUTES);
        Instant start = end.minus(days, ChronoUnit.DAYS);
        List<Object[]> rows = new ArrayList<>();
        double occupancy = 0.0;
        for (Instant bucket = start; bucket.isBefore(end); bucket = bucket.plus(15, ChronoUnit.MINUTES)) {
            ZonedDateTime local = bucket.atZone(CostaRicaCalendar.ZONE);
            int hour = local.getHour();
            double mean = hour >= 7 && hour < 10 ? 2.0 : hour >= 15 && hour < 18 ? 1.7 : hour >= 22 || hour < 6 ? 0.2 : 1.0;
            int arrivals = poisson(mean, random);
            occupancy = Math.max(0.0, occupancy + arrivals - 2.6);
            double wait = Math.min(45.0, occupancy * 1.5 + random.nextDouble() * 2.0);
            for (int i = 0; i < arrivals; i++) {
                Instant entered = bucket.plusSeconds(60L + random.nextInt(13 * 60));
                Instant validated = entered.plusSeconds(Math.round(wait * 60));
                Instant exited = validated.plusSeconds(300);
                rows.add(new Object[] {YARD, G1, java.sql.Timestamp.from(entered), java.sql.Timestamp.from(validated),
                    java.sql.Timestamp.from(validated), java.sql.Timestamp.from(exited), (int) Math.round(wait * 60), 300});
            }
        }
        jdbcTemplate.batchUpdate(INSERT, rows);
        assertThat(rows).hasSizeGreaterThan(200);
    }

    private static int poisson(double mean, Random random) {
        double limit = Math.exp(-mean);
        double product = 1.0;
        int count = 0;
        do {
            count++;
            product *= random.nextDouble();
        } while (product > limit && count < 50);
        return count - 1;
    }
}
