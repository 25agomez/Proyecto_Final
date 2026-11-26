package cr.logisticaportuaria.fleet.application.dto;

import cr.logisticaportuaria.fleet.infrastructure.persistence.WorkOrderRepository;
import java.time.Instant;
import java.util.UUID;

/**
 * Work order of the fleet.
 *
 * @param id            identifier
 * @param yardId        yard
 * @param vehicleId     assigned vehicle, may be null
 * @param vehicleCode   code of the assigned vehicle, may be null
 * @param containerCode container, may be null
 * @param orderType     type of order
 * @param fromSlotId    origin slot, may be null
 * @param toSlotId      destination slot, may be null
 * @param priority      priority (1 = highest)
 * @param status        status
 * @param createdAt     creation instant
 * @param startedAt     start instant, may be null
 * @param completedAt   completion instant, may be null
 */
public record WorkOrderResponse(UUID id, UUID yardId, UUID vehicleId, String vehicleCode,
        String containerCode, String orderType, UUID fromSlotId, UUID toSlotId, int priority,
        String status, Instant createdAt, Instant startedAt, Instant completedAt) {

    /**
     * @param row row of the repository
     * @return the response
     */
    public static WorkOrderResponse from(WorkOrderRepository.WorkOrderRow row) {
        return new WorkOrderResponse(row.id(), row.yardId(), row.vehicleId(), row.vehicleCode(),
                row.containerCode(), row.orderType(), row.fromSlotId(), row.toSlotId(),
                row.priority(), row.status(), row.createdAt(), row.startedAt(), row.completedAt());
    }
}
