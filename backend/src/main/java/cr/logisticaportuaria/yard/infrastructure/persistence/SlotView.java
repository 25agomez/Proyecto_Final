package cr.logisticaportuaria.yard.infrastructure.persistence;

import java.util.UUID;

/**
 * Projection of a slot with its current occupancy.
 */
public interface SlotView {

    /**
     * @return identifier of the slot
     */
    UUID getId();

    /**
     * @return code of the block of the slot
     */
    String getBlockCode();

    /**
     * @return bay of the slot
     */
    Integer getBay();

    /**
     * @return row of the slot
     */
    Integer getRow();

    /**
     * @return tier of the slot
     */
    Integer getTier();

    /**
     * @return size of the container that fits in the slot
     */
    Integer getSizeFt();

    /**
     * @return type of cargo accepted by the slot
     */
    String getSlotType();

    /**
     * @return whether the slot has a power outlet
     */
    Boolean getHasPower();

    /**
     * @return whether the slot is occupied
     */
    Boolean getOccupied();

    /**
     * @return code of the container that occupies the slot (null when it is free)
     */
    String getContainerCode();
}
