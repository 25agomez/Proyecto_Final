package cr.logisticaportuaria.optimizer.domain;

import cr.logisticaportuaria.yard.domain.SlotType;
import java.util.UUID;

/**
 * Immutable view of a yard slot used by the stacking engine.
 *
 * <p>The distance to the gate is not stored in the schema ({@code yard_slot} has no such
 * column), so it is derived deterministically from the coordinates by
 * {@link DistanceModel}. The value is the same for every run of the same seed, which keeps
 * the benchmark reproducible.
 *
 * @param id                  identifier of the slot ({@code yard_slot.id})
 * @param blockCode           code of the block (for example {@code B3})
 * @param bay                 bay (column along the quay)
 * @param row                 row inside the block
 * @param tier                stacking level, starting at 1
 * @param sizeFt              size of the slot in feet (20 or 40)
 * @param slotType            cargo accepted by the slot
 * @param hasPower            whether the slot has a reefer plug
 * @param maxTier             maximum tier of the block
 * @param distanceToGateMeters derived distance to the gate/bench
 */
public record SlotNode(
        UUID id,
        String blockCode,
        int bay,
        int row,
        int tier,
        int sizeFt,
        SlotType slotType,
        boolean hasPower,
        int maxTier,
        double distanceToGateMeters) {

    /**
     * Validates the coordinates.
     */
    public SlotNode {
        if (id == null) {
            throw new IllegalArgumentException("El slot necesita identificador.");
        }
        if (blockCode == null || blockCode.isBlank()) {
            throw new IllegalArgumentException("El slot necesita código de bloque.");
        }
        if (bay < 1 || row < 1 || tier < 1) {
            throw new IllegalArgumentException("Bahía, fila y nivel deben ser positivos.");
        }
        if (tier > maxTier) {
            throw new IllegalArgumentException(
                    "El nivel %d supera la altura máxima %d del bloque %s.".formatted(tier, maxTier, blockCode));
        }
        if (sizeFt != 20 && sizeFt != 40) {
            throw new IllegalArgumentException("El slot debe ser de 20 o 40 pies.");
        }
        if (slotType == null) {
            throw new IllegalArgumentException("El slot necesita tipo de carga.");
        }
        if (distanceToGateMeters < 0) {
            throw new IllegalArgumentException("La distancia a la puerta no puede ser negativa.");
        }
    }

    /**
     * @return human readable code of the slot, for example {@code A-3-2-1}
     */
    public String code() {
        return blockCode + "-" + bay + "-" + row + "-" + tier;
    }

    /**
     * @return whether the slot can host a reefer container
     */
    public boolean supportsReefer() {
        return hasPower;
    }
}
