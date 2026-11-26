package cr.logisticaportuaria.fleet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the telemetry payload validation and normalization.
 */
class FleetTelemetryTest {

    private static final UUID VEHICLE = UUID.randomUUID();

    @Test
    @DisplayName("el codigo de contenedor se normaliza a mayusculas y sin espacios")
    void normalizesContainerCode() {
        FleetTelemetry telemetry = telemetry("csqu 3054383").normalized();
        assertThat(telemetry.containerCode()).isEqualTo("CSQU3054383");
    }

    @Test
    @DisplayName("un codigo vacio se convierte en nulo")
    void emptyContainerCode() {
        assertThat(telemetry("  ").normalized().containerCode()).isNull();
    }

    @Test
    @DisplayName("latitud fuera de rango")
    void invalidLatitude() {
        assertThatThrownBy(() -> new FleetTelemetry(VEHICLE, Instant.now(), 120.0, 0.0, 0.0, 0.0,
                50.0, null, FleetStatus.LIBRE, null, null).normalized())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lat");
    }

    @Test
    @DisplayName("velocidad negativa")
    void invalidSpeed() {
        assertThatThrownBy(() -> new FleetTelemetry(VEHICLE, Instant.now(), 0.0, 0.0, -1.0, 0.0,
                50.0, null, FleetStatus.LIBRE, null, null).normalized())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("speedKmh");
    }

    @Test
    @DisplayName("estado obligatorio")
    void missingStatus() {
        assertThatThrownBy(() -> new FleetTelemetry(VEHICLE, Instant.now(), 0.0, 0.0, 0.0, 0.0,
                50.0, null, null, null, null).normalized())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }

    @Test
    @DisplayName("el estado se parsea sin distinguir mayusculas")
    void parsesStatus() {
        assertThat(FleetStatus.parse("en_movimiento")).isEqualTo(FleetStatus.EN_MOVIMIENTO);
        assertThat(FleetStatus.parse("desconocido")).isNull();
    }

    private static FleetTelemetry telemetry(String containerCode) {
        return new FleetTelemetry(VEHICLE, Instant.now(), 9.9908, -83.0350, 5.0, 90.0, 80.0, null,
                FleetStatus.EN_MOVIMIENTO, null, containerCode);
    }
}
