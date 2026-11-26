package cr.logisticaportuaria.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the ingestion counters and the latency percentiles.
 */
class FleetMetricsTest {

    @Test
    @DisplayName("los contadores reflejan cada resultado")
    void counters() {
        FleetMetrics metrics = new FleetMetrics();
        metrics.received();
        metrics.persisted();
        metrics.received();
        metrics.duplicate();
        metrics.received();
        metrics.impossibleJump();
        metrics.alert();
        metrics.broadcast();

        FleetMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.received()).isEqualTo(3);
        assertThat(snapshot.persisted()).isEqualTo(1);
        assertThat(snapshot.duplicates()).isEqualTo(1);
        assertThat(snapshot.impossibleJumps()).isEqualTo(1);
        assertThat(snapshot.alerts()).isEqualTo(1);
        assertThat(snapshot.broadcasts()).isEqualTo(1);
        assertThat(snapshot.received()).isEqualTo(snapshot.persisted() + snapshot.duplicates()
                + snapshot.outOfOrder() + snapshot.impossibleJumps() + snapshot.invalid()
                + snapshot.unknownVehicles());
    }

    @Test
    @DisplayName("percentiles de latencia en milisegundos")
    void latencyPercentiles() {
        FleetMetrics metrics = new FleetMetrics();
        for (int i = 1; i <= 100; i++) {
            metrics.latency(i * 1_000_000L);
        }
        FleetMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.latencyP50Ms()).isEqualTo(50.0);
        assertThat(snapshot.latencyP95Ms()).isEqualTo(95.0);
    }

    @Test
    @DisplayName("sin muestras los percentiles son cero")
    void emptyLatency() {
        FleetMetrics.Snapshot snapshot = new FleetMetrics().snapshot();
        assertThat(snapshot.latencyP50Ms()).isZero();
        assertThat(snapshot.latencyP95Ms()).isZero();
    }
}
