package cr.logisticaportuaria.gate.application.dto;

import cr.logisticaportuaria.gate.domain.VisitState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Visit of the digital logbook with its timeline.
 *
 * @param id             identifier of the visit
 * @param yardId         yard of the visit
 * @param gateId         gate of the visit
 * @param appointmentId  appointment of the visit (may be null)
 * @param plate          plate of the truck (may be null)
 * @param containerCode  container of the visit (may be null)
 * @param direction      IN or OUT
 * @param state          state of the visit
 * @param result         result of the reconciliation
 * @param reason         explanation in Spanish
 * @param enteredAt      instant of the arrival
 * @param validatedAt    instant of the validation
 * @param assignedAt     instant of the assignment
 * @param processingAt   instant of the start of the operation
 * @param exitedAt       instant of the exit
 * @param waitSeconds    waiting time at the gate
 * @param yardSeconds    time inside the yard
 * @param serviceSeconds service time
 * @param timeline       stages of the visit
 */
public record VisitResponse(
        UUID id,
        UUID yardId,
        UUID gateId,
        UUID appointmentId,
        String plate,
        String containerCode,
        String direction,
        String state,
        String result,
        String reason,
        Instant enteredAt,
        Instant validatedAt,
        Instant assignedAt,
        Instant processingAt,
        Instant exitedAt,
        Integer waitSeconds,
        Integer yardSeconds,
        Integer serviceSeconds,
        List<TimelineEntry> timeline) {

    /**
     * Stage of the timeline of a visit.
     *
     * @param stage  state that was reached
     * @param at     instant of the stage
     * @param actor  who registered it
     * @param detail details of the stage
     */
    public record TimelineEntry(
            VisitState stage,
            Instant at,
            String actor,
            java.util.Map<String, Object> detail) {
    }
}
