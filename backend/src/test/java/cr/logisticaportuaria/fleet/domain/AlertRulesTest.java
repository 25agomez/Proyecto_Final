package cr.logisticaportuaria.fleet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the alert rules: every rule with a positive and a negative case.
 */
class AlertRulesTest {

    private static final UUID YARD = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    @Test
    @DisplayName("exceso de velocidad sobre el limite")
    void speedAboveLimit() {
        Optional<FleetAlert> alert = AlertRules.speed(state(10, 10, NOW), telemetry(10, 10, NOW,
                FleetStatus.EN_MOVIMIENTO, 30.0, 80.0), 15.0);
        assertThat(alert).isPresent();
        assertThat(alert.get().type()).isEqualTo(AlertType.EXCESO_VELOCIDAD);
        assertThat(alert.get().severity()).isEqualTo(AlertType.Severity.ALTA);
    }

    @Test
    @DisplayName("la velocidad dentro del limite no genera alerta")
    void speedWithinLimit() {
        assertThat(AlertRules.speed(state(10, 10, NOW), telemetry(10, 10, NOW,
                FleetStatus.EN_MOVIMIENTO, 12.0, 80.0), 15.0)).isEmpty();
    }

    @Test
    @DisplayName("bateria por debajo del umbral")
    void lowBattery() {
        Optional<FleetAlert> alert = AlertRules.lowBattery(state(10, 10, NOW), telemetry(10, 10,
                NOW, FleetStatus.LIBRE, 0.0, 12.0), 20.0);
        assertThat(alert).isPresent();
        assertThat(alert.get().type()).isEqualTo(AlertType.BATERIA_BAJA);
    }

    @Test
    @DisplayName("sin dato de bateria no hay alerta")
    void missingBattery() {
        assertThat(AlertRules.lowBattery(state(10, 10, NOW), telemetry(10, 10, NOW,
                FleetStatus.LIBRE, 0.0, null), 20.0)).isEmpty();
    }

    @Test
    @DisplayName("ralenti prolongado")
    void prolongedIdle() {
        Optional<FleetAlert> alert = AlertRules.prolongedIdle(state(10, 10, NOW),
                telemetry(10, 10, NOW, FleetStatus.LIBRE, 0.0, 90.0), 10, 1.0,
                NOW.minusSeconds(11 * 60));
        assertThat(alert).isPresent();
        assertThat(alert.get().type()).isEqualTo(AlertType.RALENTI);
    }

    @Test
    @DisplayName("detenido menos tiempo que el umbral no alerta")
    void shortIdle() {
        assertThat(AlertRules.prolongedIdle(state(10, 10, NOW), telemetry(10, 10, NOW,
                FleetStatus.LIBRE, 0.0, 90.0), 10, 1.0, NOW.minusSeconds(5 * 60))).isEmpty();
    }

    @Test
    @DisplayName("zona prohibida")
    void forbiddenZone() {
        List<Geofence> zones = List.of(zone("Mantenimiento", GeofenceKind.PROHIBIDA, 200, 60, 260, 100));
        Optional<FleetAlert> alert = AlertRules.forbiddenZone(state(230, 80, NOW),
                telemetry(230, 80, NOW, FleetStatus.EN_MOVIMIENTO, 5.0, 90.0), zones);
        assertThat(alert).isPresent();
        assertThat(alert.get().type()).isEqualTo(AlertType.ZONA_PROHIBIDA);
        assertThat(alert.get().message()).contains("Mantenimiento");
    }

    @Test
    @DisplayName("fuera de la zona prohibida no hay alerta")
    void outsideForbiddenZone() {
        List<Geofence> zones = List.of(zone("Mantenimiento", GeofenceKind.PROHIBIDA, 200, 60, 260, 100));
        assertThat(AlertRules.forbiddenZone(state(10, 10, NOW), telemetry(10, 10, NOW,
                FleetStatus.EN_MOVIMIENTO, 5.0, 90.0), zones)).isEmpty();
    }

    @Test
    @DisplayName("estado AVERIA genera alerta")
    void breakdown() {
        assertThat(AlertRules.breakdown(state(10, 10, NOW), telemetry(10, 10, NOW,
                FleetStatus.AVERIA, 0.0, 90.0))).get()
                .extracting(FleetAlert::type).isEqualTo(AlertType.AVERIA);
    }

    @Test
    @DisplayName("silencio mayor que el umbral")
    void silence() {
        Optional<FleetAlert> alert = AlertRules.silence(state(10, 10, NOW.minusSeconds(120)), NOW, 60);
        assertThat(alert).isPresent();
        assertThat(alert.get().type()).isEqualTo(AlertType.SIN_SENAL);
    }

    @Test
    @DisplayName("telemetria reciente no es silencio")
    void noSilence() {
        assertThat(AlertRules.silence(state(10, 10, NOW.minusSeconds(10)), NOW, 60)).isEmpty();
    }

    @Test
    @DisplayName("salto imposible")
    void impossibleJump() {
        FleetAlert alert = AlertRules.impossibleJump(state(0, 0, NOW), telemetry(400, 0, NOW,
                FleetStatus.EN_MOVIMIENTO, 20.0, 80.0), 1_440.0);
        assertThat(alert.type()).isEqualTo(AlertType.SALTO_GPS);
        assertThat(alert.message()).contains("salto imposible");
    }

    @Test
    @DisplayName("entrada y salida de zona")
    void zoneTransitions() {
        Geofence gate = zone("Puerta", GeofenceKind.PUERTA, 0, 0, 120, 40);
        List<FleetAlert> entries = AlertRules.zoneTransitions(List.of(gate), Set.of(),
                Set.of(gate.id()), state(50, 20, NOW), NOW);
        assertThat(entries).singleElement()
                .extracting(FleetAlert::type).isEqualTo(AlertType.ENTRADA_ZONA);
        List<FleetAlert> exits = AlertRules.zoneTransitions(List.of(gate), Set.of(gate.id()),
                Set.of(), state(200, 20, NOW), NOW);
        assertThat(exits).singleElement()
                .extracting(FleetAlert::type).isEqualTo(AlertType.SALIDA_ZONA);
    }

    @Test
    @DisplayName("las zonas que contienen un punto")
    void zonesContaining() {
        Geofence gate = zone("Puerta", GeofenceKind.PUERTA, 0, 0, 120, 40);
        Geofence hazmat = zone("Hazmat", GeofenceKind.HAZMAT, 300, 0, 420, 40);
        assertThat(AlertRules.zonesContaining(List.of(gate, hazmat), 50, 20))
                .containsExactly(gate.id());
        assertThat(AlertRules.zonesContaining(List.of(gate, hazmat), 350, 20))
                .containsExactly(hazmat.id());
        assertThat(AlertRules.zonesContaining(List.of(gate, hazmat), 200, 90)).isEmpty();
    }

    private static VehicleState state(double x, double y, Instant at) {
        return new VehicleState(VEHICLE, YARD, "VEH-01", "MONTACARGAS", FleetStatus.LIBRE, at, x, y,
                0.0, 90.0, null, null, null);
    }

    private static FleetTelemetry telemetry(double x, double y, Instant at, FleetStatus status,
            double speed, Double battery) {
        return new FleetTelemetry(VEHICLE, at, YardFrame.latOf(y), YardFrame.lonOf(x), speed, 90.0,
                battery, null, status, null, null);
    }

    private static Geofence zone(String name, GeofenceKind kind, double x1, double y1, double x2,
            double y2) {
        String json = String.format("""
                {"type":"Polygon","coordinates":[[[%s,%s],[%s,%s],[%s,%s],[%s,%s],[%s,%s]]]}
                """, x1, y1, x2, y1, x2, y2, x1, y2, x1, y1);
        try {
            return new Geofence(UUID.randomUUID(), YARD, name, kind, GeoPolygon.fromGeoJson(
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
