package cr.logisticaportuaria.fleet.application.dto;

import java.util.UUID;

/**
 * Request of the assignment of a work order.
 *
 * @param vehicleId vehicle to assign; null means the nearest free vehicle
 */
public record AssignWorkOrderRequest(UUID vehicleId) {
}
