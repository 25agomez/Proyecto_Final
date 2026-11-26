package cr.logisticaportuaria.gate.application.dto;

import java.util.UUID;

/**
 * Task of the manual review queue.
 *
 * @param id             identifier of the task
 * @param gateEventId    event that generated the task
 * @param visitId        visit affected (may be null)
 * @param kind           PLATE or CONTAINER
 * @param rawText        raw text of the reading
 * @param normalizedText normalized text of the reading
 * @param reason         reason of the manual review
 * @param status         status of the task
 * @param resolvedText   text confirmed or corrected by the operator
 * @param resolvedBy     operator that resolved the task
 * @param resolvedAt     instant of the resolution
 */
public record ReviewTaskResponse(
        UUID id,
        UUID gateEventId,
        UUID visitId,
        String kind,
        String rawText,
        String normalizedText,
        String reason,
        String status,
        String resolvedText,
        String resolvedBy,
        java.time.Instant resolvedAt) {
}
