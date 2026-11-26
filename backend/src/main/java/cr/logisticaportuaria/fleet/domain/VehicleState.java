package cr.logisticaportuaria.fleet.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Last known state of a vehicle.
 *
 * @param vehicleId     identifier of the vehicle
 * @param yardId        identifier of the yard
 * @param code          code of the vehicle ({@code VEH-01})
 * @param vehicleType   type of the vehicle
 * @param status        operational status
 * @param at            instant of the last measurement
 * @param x             position in the local frame, in meters
 * @param y             position in the local frame, in meters
 * @param speedKmh      speed in km/h
 * @param batteryPct    battery percentage, may be null
 * @param fuelPct       fuel percentage, may be null
 * @param workOrderId   work order in progress, may be null
 * @param containerCode container being moved, may be null
 */
public record VehicleState(
        UUID vehicleId,
        UUID yardId,
        String code,
        String vehicleType,
        FleetStatus status,
        Instant at,
        double x,
        double y,
        double speedKmh,
        Double batteryPct,
        Double fuelPct,
        UUID workOrderId,
        String containerCode) {

    /**
     * @param telemetry telemetry that updated the vehicle
     * @return the vehicle with the new measurement
     */
    public VehicleState withTelemetry(FleetTelemetry telemetry) {
        return new VehicleState(vehicleId, yardId, code, vehicleType, telemetry.status(),
                telemetry.ts(), telemetry.x(), telemetry.y(), telemetry.speedKmh(),
                telemetry.batteryPct(), telemetry.fuelPct(), telemetry.workOrderId(),
                telemetry.containerCode());
    }

    /**
     * @param reference instant of comparison
     * @param silenceSeconds seconds without telemetry that count as silence
     * @return whether the vehicle has been silent for longer than the threshold
     */
    public boolean isSilent(Instant reference, int silenceSeconds) {
        return at != null && reference.isAfter(at.plusSeconds(silenceSeconds));
    }
}
