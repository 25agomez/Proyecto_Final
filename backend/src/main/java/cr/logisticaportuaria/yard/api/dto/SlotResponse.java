package cr.logisticaportuaria.yard.api.dto;

import java.util.UUID;

/**
 * Slot of the API.
 *
 * @param id                 identifier of the slot
 * @param blockCode          code of the block
 * @param bay                bay of the slot
 * @param row                row of the slot
 * @param tier               tier of the slot
 * @param sizeFt             size of the container that fits in the slot
 * @param slotType           type of cargo accepted (dry, reefer, hazmat)
 * @param hasPower           whether the slot has a power outlet
 * @param distanceToGateMeters distance to the gate (null until the geometry is loaded)
 * @param occupied           whether the slot is occupied
 * @param containerCode      code of the container that occupies the slot
 */
public record SlotResponse(
        UUID id,
        String blockCode,
        int bay,
        int row,
        int tier,
        int sizeFt,
        String slotType,
        boolean hasPower,
        Double distanceToGateMeters,
        boolean occupied,
        String containerCode) {
}
