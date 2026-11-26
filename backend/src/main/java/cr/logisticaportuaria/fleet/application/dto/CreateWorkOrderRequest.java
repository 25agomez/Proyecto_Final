package cr.logisticaportuaria.fleet.application.dto;

import java.util.UUID;

/**
 * Request of a new work order.
 *
 * @param yardId        yard, optional when it can be resolved from the configuration
 * @param containerCode container, may be null for a generic movement
 * @param orderType     INGRESO, RETIRO, REUBICACION or MOVIMIENTO
 * @param fromSlotId    origin slot, may be null
 * @param toSlotId      destination slot, may be null
 * @param priority      priority between 1 (highest) and 10 (lowest), default 5
 */
public record CreateWorkOrderRequest(UUID yardId, String containerCode, String orderType,
        UUID fromSlotId, UUID toSlotId, Integer priority) {
}
