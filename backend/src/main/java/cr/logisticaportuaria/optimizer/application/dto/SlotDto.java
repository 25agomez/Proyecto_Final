package cr.logisticaportuaria.optimizer.application.dto;

import cr.logisticaportuaria.optimizer.domain.ContainerUnit;
import cr.logisticaportuaria.optimizer.domain.SlotNode;
import cr.logisticaportuaria.yard.domain.SlotType;
import java.util.UUID;

/**
 * Slot of the {@code Slot} schema of the OpenAPI contract.
 *
 * @param id                    identifier of the slot
 * @param blockCode             code of the block
 * @param bay                   bay
 * @param row                   row
 * @param tier                  tier
 * @param sizeFt                size in feet
 * @param slotType              cargo accepted by the slot
 * @param hasPower              whether the slot has a power outlet
 * @param distanceToGateMeters  derived distance to the gate
 * @param occupied              whether the slot is occupied
 * @param containerCode         container stacked there (null when it is free)
 */
public record SlotDto(
        UUID id,
        String blockCode,
        int bay,
        int row,
        int tier,
        int sizeFt,
        SlotType slotType,
        boolean hasPower,
        double distanceToGateMeters,
        boolean occupied,
        String containerCode) {

    /**
     * @param slot       slot to map
     * @param container  container stacked there, or null when the slot is free
     * @return the DTO
     */
    public static SlotDto from(SlotNode slot, ContainerUnit container) {
        return new SlotDto(
                slot.id(),
                slot.blockCode(),
                slot.bay(),
                slot.row(),
                slot.tier(),
                slot.sizeFt(),
                slot.slotType(),
                slot.hasPower(),
                slot.distanceToGateMeters(),
                container != null,
                container == null ? null : container.code());
    }
}
