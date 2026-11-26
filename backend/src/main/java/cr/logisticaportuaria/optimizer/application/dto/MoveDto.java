package cr.logisticaportuaria.optimizer.application.dto;

import cr.logisticaportuaria.optimizer.domain.Move;
import java.util.UUID;

/**
 * Step of an {@code OptimizerPlan}.
 *
 * @param order         order of execution (1-based)
 * @param action        {@code PLACE}, {@code MOVE} or {@code REMOVE}
 * @param containerCode BIC code of the container
 * @param fromSlotId    slot the container leaves (null for {@code PLACE})
 * @param toSlotId      slot the container goes to (null for {@code REMOVE})
 * @param toSlotCode    human readable code of the target slot
 * @param restows       restows caused by the move
 */
public record MoveDto(
        int order,
        String action,
        String containerCode,
        UUID fromSlotId,
        UUID toSlotId,
        String toSlotCode,
        int restows) {

    /**
     * @param move move to map
     * @return the DTO
     */
    public static MoveDto from(Move move) {
        return new MoveDto(move.order(), move.action(), move.containerCode(), move.fromSlotId(), move.toSlotId(),
                move.toSlotCode(), move.restows());
    }
}
