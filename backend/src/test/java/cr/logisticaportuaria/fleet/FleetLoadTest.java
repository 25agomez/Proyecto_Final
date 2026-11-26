package cr.logisticaportuaria.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import cr.logisticaportuaria.fleet.application.FleetMetrics;
import cr.logisticaportuaria.fleet.application.FleetStateCache;
import cr.logisticaportuaria.fleet.application.IngestResult;
import cr.logisticaportuaria.fleet.application.VehicleDirectory;
import cr.logisticaportuaria.fleet.domain.YardFrame;
import cr.logisticaportuaria.fleet.infrastructure.mqtt.FleetTelemetryMqttSubscriber;
import cr.logisticaportuaria.fleet.infrastructure.websocket.FleetBroadcaster;
import cr.logisticaportuaria.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Load test of the telemetry pipeline.
 *
 * <p>Publishes {@code fleet.load.vehicles} (50) messages per second for
 * {@code fleet.load.seconds} (10 by default, {@code -Dfleet.load.seconds=300} for the full
 * five-minute run) through the same method the MQTT callback uses, while a flusher thread
 * calls {@code FleetBroadcaster.flush()} every second, like the production scheduler. At the
 * end it compares the messages sent with the rows in {@code vehicle_position} (no losses) and
 * reports p50/p95 of the reception-to-broadcast latency.
 *
 * <p>Docker is not available in this environment (MEMORY.md §9 B-001), so the messages are not
 * routed through Mosquitto: the metric is the latency of the ingestion + throttle +
 * {@code convertAndSend}, not the broker hop.
 */
@SpringBootTest
@ActiveProfiles("test")
class FleetLoadTest extends AbstractIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(FleetLoadTest.class);

    private static final UUID YARD = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final int VEHICLES = 50;
    private static final int THREADS = 4;
    private static final String LOAD_PREFIX = "LOAD-";

    @Autowired
    private FleetTelemetryMqttSubscriber subscriber;

    @Autowired
    private FleetBroadcaster broadcaster;

    @Autowired
    private FleetMetrics metrics;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FleetStateCache stateCache;

    @Autowired
    private VehicleDirectory vehicleDirectory;

    private List<UUID> vehicleIds;

    @BeforeEach
    void createLoadVehicles() {
        jdbcTemplate.update("DELETE FROM vehicle_position WHERE vehicle_id IN "
                + "(SELECT id FROM vehicle WHERE code LIKE ?)", LOAD_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM vehicle_last_state WHERE vehicle_id IN "
                + "(SELECT id FROM vehicle WHERE code LIKE ?)", LOAD_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM vehicle WHERE code LIKE ?", LOAD_PREFIX + "%");
        for (int i = 1; i <= VEHICLES; i++) {
            jdbcTemplate.update("""
                    INSERT INTO vehicle (yard_id, code, vehicle_type, status)
                    VALUES (?, ?, 'MONTACARGAS', 'LIBRE')
                    """, YARD, String.format(Locale.ROOT, "%s%02d", LOAD_PREFIX, i));
        }
        vehicleIds = jdbcTemplate.queryForList(
                "SELECT id FROM vehicle WHERE code LIKE ? ORDER BY code", UUID.class,
                LOAD_PREFIX + "%");
        stateCache.clear();
        vehicleDirectory.invalidate();
        broadcaster.flush();
    }

    @AfterEach
    void removeLoadVehicles() {
        broadcaster.flush();
        jdbcTemplate.update("DELETE FROM vehicle_position WHERE vehicle_id IN "
                + "(SELECT id FROM vehicle WHERE code LIKE ?)", LOAD_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM vehicle_last_state WHERE vehicle_id IN "
                + "(SELECT id FROM vehicle WHERE code LIKE ?)", LOAD_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM vehicle WHERE code LIKE ?", LOAD_PREFIX + "%");
        stateCache.clear();
        vehicleDirectory.invalidate();
    }

    @Test
    @DisplayName("50 vehiculos x 1 msg/s sin perdidas y p95 <= 2 s")
    void sustainsTheLoadWithoutLosingMessages() throws Exception {
        int seconds = Integer.getInteger("fleet.load.seconds", 10);
        Instant base = Instant.now();

        long rowsBefore = countPositions();
        FleetMetrics.Snapshot before = metrics.snapshot();
        AtomicLong sent = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        AtomicLong attempted = new AtomicLong();

        ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, "load-flusher"));
        flusher.scheduleAtFixedRate(() -> {
            try {
                broadcaster.flush();
            } catch (RuntimeException exception) {
                LOGGER.warn("flush fallo: {}", exception.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);

        ExecutorService publishers = Executors.newFixedThreadPool(THREADS,
                runnable -> new Thread(runnable, "load-publisher"));
        CountDownLatch done = new CountDownLatch(THREADS);
        long startedAt = System.nanoTime();
        for (int thread = 0; thread < THREADS; thread++) {
            int slice = thread;
            publishers.submit(() -> {
                try {
                    for (int tick = 0; tick < seconds; tick++) {
                        long target = startedAt + (tick + 1) * 1_000_000_000L;
                        for (int index = slice; index < VEHICLES; index += THREADS) {
                            UUID vehicleId = vehicleIds.get(index);
                            Instant ts = base.plusSeconds(tick);
                            double x = 40 + index * 6.0;
                            double y = 20 + (tick % 5) * 2.0;
                            IngestResult result = subscriber.handleTopic(topic(vehicleId),
                                    payload(vehicleId, ts, x, y));
                            attempted.incrementAndGet();
                            if (result == IngestResult.ACCEPTED) {
                                sent.incrementAndGet();
                            } else {
                                rejected.incrementAndGet();
                            }
                        }
                        long remaining = target - System.nanoTime();
                        if (remaining > 0) {
                            TimeUnit.NANOSECONDS.sleep(remaining);
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException exception) {
                    LOGGER.error("Fallo en el hilo publicador {}", slice, exception);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(seconds + 60L, TimeUnit.SECONDS)).isTrue();
        publishers.shutdown();
        flusher.shutdown();
        flusher.awaitTermination(5, TimeUnit.SECONDS);
        broadcaster.flush();

        FleetMetrics.Snapshot after = metrics.snapshot();
        long rowsAfter = countPositions();
        long persistedDelta = after.persisted() - before.persisted();
        long receivedDelta = after.received() - before.received();
        long rowsDelta = rowsAfter - rowsBefore;

        LOGGER.info("Carga de flota: {} mensajes en {} s ({} hilos), intentos {}, rechazados {}, "
                        + "recibidos {}, persistidos {}, filas nuevas {}, p50 {} ms, p95 {} ms",
                sent.get(), seconds, THREADS, attempted.get(), rejected.get(), receivedDelta,
                persistedDelta, rowsDelta, after.latencyP50Ms(), after.latencyP95Ms());

        assertThat(attempted.get()).isEqualTo((long) VEHICLES * seconds);

        assertThat(rejected.get()).isZero();
        assertThat(receivedDelta).isEqualTo(sent.get());
        assertThat(persistedDelta).isEqualTo(sent.get());
        assertThat(rowsDelta).isEqualTo(sent.get());
        assertThat(after.latencyP95Ms()).isLessThanOrEqualTo(2_000.0);
    }

    private long countPositions() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM vehicle_position
                WHERE vehicle_id IN (SELECT id FROM vehicle WHERE code LIKE ?)
                """, Long.class, LOAD_PREFIX + "%");
        return count == null ? 0 : count;
    }

    private static String topic(UUID vehicleId) {
        return "yard/" + YARD + "/fleet/" + vehicleId + "/telemetry";
    }

    private static String payload(UUID vehicleId, Instant ts, double x, double y) {
        return String.format(Locale.ROOT,
                "{\"vehicleId\":\"%s\",\"ts\":\"%s\",\"lat\":%.8f,\"lon\":%.8f,"
                        + "\"speedKmh\":6.0,\"headingDeg\":90.0,\"batteryPct\":85.0,"
                        + "\"status\":\"EN_MOVIMIENTO\"}",
                vehicleId, ts, YardFrame.latOf(y), YardFrame.lonOf(x));
    }

    /**
     * @return the identifiers created by the test, for diagnostics
     */
    List<UUID> vehicleIds() {
        return new ArrayList<>(vehicleIds);
    }
}
