package cr.logisticaportuaria.fleet.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure alert rules of the fleet.
 *
 * <p>Every rule receives the last known state and the new measurement and returns an alert
 * when the condition holds. They do not touch the database, so they are unit tested without
 * Spring (see {@code AlertRulesTest}).
 */
public final class AlertRules {

    private AlertRules() {
    }

    /**
     * @param state current state
     * @param telemetry new measurement
     * @param speedLimitKmh configured limit
     * @return alert when the vehicle exceeds the limit
     */
    public static Optional<FleetAlert> speed(VehicleState state, FleetTelemetry telemetry,
            double speedLimitKmh) {
        if (telemetry.speedKmh() <= speedLimitKmh) {
            return Optional.empty();
        }
        return Optional.of(new FleetAlert(AlertType.EXCESO_VELOCIDAD, state.vehicleId(),
                state.code(), state.yardId(), telemetry.ts(),
                String.format(Locale.ROOT,
                        "Exceso de velocidad: %.1f km/h (limite %.1f km/h)",
                        telemetry.speedKmh(), speedLimitKmh),
                telemetry.x(), telemetry.y()));
    }

    /**
     * @param state current state
     * @param telemetry new measurement
     * @param batteryLowPct configured threshold
     * @return alert when the battery is below the threshold
     */
    public static Optional<FleetAlert> lowBattery(VehicleState state, FleetTelemetry telemetry,
            double batteryLowPct) {
        if (telemetry.batteryPct() == null || telemetry.batteryPct() >= batteryLowPct) {
            return Optional.empty();
        }
        return Optional.of(new FleetAlert(AlertType.BATERIA_BAJA, state.vehicleId(), state.code(),
                state.yardId(), telemetry.ts(),
                String.format(Locale.ROOT, "Bateria baja: %.0f %% (umbral %.0f %%)",
                        telemetry.batteryPct(), batteryLowPct),
                telemetry.x(), telemetry.y()));
    }

    /**
     * @param state current state
     * @param telemetry new measurement
     * @param idleMinutes configured idle time
     * @param idleSpeedKmh configured speed that counts as stopped
     * @param stoppedSince instant when the vehicle stopped, may be null
     * @return alert when the vehicle has been stopped for too long
     */
    public static Optional<FleetAlert> prolongedIdle(VehicleState state, FleetTelemetry telemetry,
            int idleMinutes, double idleSpeedKmh, java.time.Instant stoppedSince) {
        if (stoppedSince == null || telemetry.speedKmh() > idleSpeedKmh) {
            return Optional.empty();
        }
        Duration stopped = Duration.between(stoppedSince, telemetry.ts());
        if (stopped.toMinutes() < idleMinutes) {
            return Optional.empty();
        }
        return Optional.of(new FleetAlert(AlertType.RALENTI, state.vehicleId(), state.code(),
                state.yardId(), telemetry.ts(),
                String.format(Locale.ROOT, "Ralenti prolongado: %d min detenido",
                        stopped.toMinutes()),
                telemetry.x(), telemetry.y()));
    }

    /**
     * @param state current state
     * @param telemetry new measurement
     * @param zones forbidden zones of the yard
     * @return alert when the vehicle is inside a forbidden zone
     */
    public static Optional<FleetAlert> forbiddenZone(VehicleState state, FleetTelemetry telemetry,
            List<Geofence> zones) {
        for (Geofence zone : zones) {
            if (zone.isForbidden() && zone.polygon().contains(telemetry.x(), telemetry.y())) {
                return Optional.of(new FleetAlert(AlertType.ZONA_PROHIBIDA, state.vehicleId(),
                        state.code(), state.yardId(), telemetry.ts(),
                        "El vehiculo entro a la zona prohibida '" + zone.name() + "'",
                        telemetry.x(), telemetry.y()));
            }
        }
        return Optional.empty();
    }

    /**
     * @param state current state
     * @param telemetry new measurement
     * @return alert when the vehicle reports a breakdown
     */
    public static Optional<FleetAlert> breakdown(VehicleState state, FleetTelemetry telemetry) {
        if (telemetry.status() != FleetStatus.AVERIA) {
            return Optional.empty();
        }
        return Optional.of(new FleetAlert(AlertType.AVERIA, state.vehicleId(), state.code(),
                state.yardId(), telemetry.ts(), "El vehiculo reporto AVERIA",
                telemetry.x(), telemetry.y()));
    }

    /**
     * @param state current state
     * @param reference instant of comparison
     * @param silenceSeconds configured silence
     * @return alert when the vehicle has been silent for longer than the threshold
     */
    public static Optional<FleetAlert> silence(VehicleState state, java.time.Instant reference,
            int silenceSeconds) {
        if (!state.isSilent(reference, silenceSeconds)) {
            return Optional.empty();
        }
        long seconds = Duration.between(state.at(), reference).toSeconds();
        return Optional.of(new FleetAlert(AlertType.SIN_SENAL, state.vehicleId(), state.code(),
                state.yardId(), reference,
                "Sin senal por " + seconds + " s (ultima lectura " + state.at() + ")",
                state.x(), state.y()));
    }

    /**
     * @param state current state
     * @param telemetry rejected measurement
     * @param measuredSpeedKmh speed implied by the jump
     * @return alert when a position is rejected as impossible
     */
    public static FleetAlert impossibleJump(VehicleState state, FleetTelemetry telemetry,
            double measuredSpeedKmh) {
        return new FleetAlert(AlertType.SALTO_GPS, state.vehicleId(), state.code(),
                state.yardId(), telemetry.ts(),
                String.format(Locale.ROOT,
                        "Posicion descartada por salto imposible (%.1f km/h implicados)",
                        measuredSpeedKmh),
                telemetry.x(), telemetry.y());
    }

    /**
     * @param zones zones of the yard
     * @param x position in the local frame
     * @param y position in the local frame
     * @return identifiers of the zones that contain the point
     */
    public static Set<UUID> zonesContaining(List<Geofence> zones, double x, double y) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Geofence zone : zones) {
            if (zone.polygon().contains(x, y)) {
                ids.add(zone.id());
            }
        }
        return ids;
    }

    /**
     * @param zones zones of the yard
     * @param previous zones of the previous measurement
     * @param current zones of the new measurement
     * @param state current state
     * @param at instant of the measurement
     * @return entry and exit events between both sets
     */
    public static List<FleetAlert> zoneTransitions(List<Geofence> zones, Set<UUID> previous,
            Set<UUID> current, VehicleState state, java.time.Instant at) {
        List<FleetAlert> events = new ArrayList<>();
        for (Geofence zone : zones) {
            if (current.contains(zone.id()) && !previous.contains(zone.id())) {
                events.add(new FleetAlert(AlertType.ENTRADA_ZONA, state.vehicleId(), state.code(),
                        state.yardId(), at, "Entrada a la zona '" + zone.name() + "'",
                        state.x(), state.y()));
            } else if (!current.contains(zone.id()) && previous.contains(zone.id())) {
                events.add(new FleetAlert(AlertType.SALIDA_ZONA, state.vehicleId(), state.code(),
                        state.yardId(), at, "Salida de la zona '" + zone.name() + "'",
                        state.x(), state.y()));
            }
        }
        return events;
    }
}
