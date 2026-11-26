package cr.logisticaportuaria.fleet.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cr.logisticaportuaria.fleet.application.FleetMetrics;
import cr.logisticaportuaria.fleet.application.FleetProperties;
import cr.logisticaportuaria.fleet.domain.AlertType;
import cr.logisticaportuaria.fleet.domain.FleetAlert;
import cr.logisticaportuaria.fleet.domain.FleetStatus;
import cr.logisticaportuaria.fleet.domain.VehicleState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Unit tests of the throttled WebSocket broadcaster.
 */
class FleetBroadcasterTest {

    private static final UUID YARD = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID VEHICLE = UUID.fromString("70000000-0000-7000-8000-000000000001");

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final FleetMetrics metrics = new FleetMetrics();
    private final FleetBroadcaster broadcaster =
            new FleetBroadcaster(template, metrics, new FleetProperties());

    @Test
    @DisplayName("solo se envia la ultima posicion de cada vehiculo en el agregado")
    void throttlesToOnePositionPerVehicle() {
        broadcaster.queue(state(Instant.parse("2026-10-22T12:00:00Z"), 10), System.nanoTime());
        broadcaster.queue(state(Instant.parse("2026-10-22T12:00:01Z"), 20), System.nanoTime());

        assertThat(broadcaster.pendingCount()).isEqualTo(1);
        assertThat(broadcaster.flush()).isEqualTo(1);
        verify(template).convertAndSend(eq("/topic/yards/" + YARD + "/fleet"), anyList());
        assertThat(broadcaster.pendingCount()).isZero();
        assertThat(metrics.snapshot().broadcasts()).isEqualTo(1);
    }

    @Test
    @DisplayName("una posicion mas vieja no reemplaza a la nueva")
    void keepsTheNewestPosition() {
        broadcaster.queue(state(Instant.parse("2026-10-22T12:00:05Z"), 20), System.nanoTime());
        broadcaster.queue(state(Instant.parse("2026-10-22T12:00:00Z"), 10), System.nanoTime());
        broadcaster.flush();
        assertThat(broadcaster.pendingCount()).isZero();
    }

    @Test
    @DisplayName("las alertas se envian al tema de alertas")
    void broadcastsAlerts() {
        FleetAlert alert = new FleetAlert(AlertType.ZONA_PROHIBIDA, VEHICLE, "VEH-01", YARD,
                Instant.now(), "zona prohibida", 230, 80);
        broadcaster.broadcastAlert(alert);
        verify(template).convertAndSend("/topic/yards/" + YARD + "/alerts", alert);
    }

    @Test
    @DisplayName("la latencia del agregado se registra en el KPI")
    void recordsLatency() {
        broadcaster.queue(state(Instant.parse("2026-10-22T12:00:00Z"), 10),
                System.nanoTime() - 5_000_000L);
        broadcaster.flush();
        assertThat(metrics.snapshot().latencyP50Ms()).isPositive();
    }

    @Test
    @DisplayName("un flush sin posiciones no envia nada")
    void emptyFlush() {
        assertThat(broadcaster.flush()).isZero();
        verify(template, org.mockito.Mockito.never()).convertAndSend(any(String.class), any(Object.class));
    }

    private VehicleState state(Instant at, double x) {
        return new VehicleState(VEHICLE, YARD, "VEH-01", "MONTACARGAS", FleetStatus.EN_MOVIMIENTO, at,
                x, 5, 6.0, 80.0, null, null, null);
    }
}
