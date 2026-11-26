package cr.logisticaportuaria.fleet.domain;

import java.util.UUID;

/**
 * Master data of a vehicle, read from {@code vehicle}.
 *
 * @param id          identifier
 * @param yardId      identifier of the yard
 * @param code        code shown to the operator
 * @param vehicleType type of the vehicle
 * @param status      last persisted status
 */
public record VehicleInfo(UUID id, UUID yardId, String code, String vehicleType, FleetStatus status) {
}
