package cr.logisticaportuaria.optimizer.domain;

import java.util.UUID;

/**
 * One step of a stacking plan.
 *
 * @param order         order of execution (1-based)
 * @param action        {@code PLACE}, {@code MOVE} or {@code REMOVE}
 * @param containerCode BIC code of the container
 * @param fromSlotId    slot the container leaves (null for {@code PLACE})
 * @param toSlotId      slot the container goes to (null for {@code REMOVE})
 * @param toSlotCode    human readable code of the target slot
 * @param restows       restows caused by this move
 */
public record Move(
        int order,
        String action,
        String containerCode,
        UUID fromSlotId,
        UUID toSlotId,
        String toSlotCode,
        int restows) {

    /**
     * @param order         order of execution
     * @param containerCode container to place
     * @param slot          target slot
     * @return a {@code PLACE} move
     */
    public static Move place(int order, String containerCode, SlotNode slot) {
        return new Move(order, "PLACE", containerCode, null, slot.id(), slot.code(), 0);
    }

    /**
     * @param order         order of execution
     * @param containerCode container to move
     * @param from          current slot
     * @param to            target slot
     * @param restows       restows caused by the move
     * @return a {@code MOVE} move
     */
    public static Move move(int order, String containerCode, SlotNode from, SlotNode to, int restows) {
        return new Move(order, "MOVE", containerCode, from.id(), to.id(), to.code(), restows);
    }
}
