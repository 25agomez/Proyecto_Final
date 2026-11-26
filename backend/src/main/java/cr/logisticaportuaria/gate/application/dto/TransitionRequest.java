package cr.logisticaportuaria.gate.application.dto;

import cr.logisticaportuaria.gate.domain.VisitState;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request to advance the state of a visit.
 *
 * @param targetState state to move to
 * @param slotId      slot assigned to the visit (optional)
 * @param note        free note for the timeline
 */
public record TransitionRequest(
        @NotNull VisitState targetState,
        UUID slotId,
        String note) {
}
