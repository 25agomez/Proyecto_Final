package cr.logisticaportuaria.gate.application.dto;

import java.util.UUID;

/**
 * Answer of the ingestion of a reading.
 *
 * @param id        identifier of the stored event
 * @param eventId   identifier provided by the edge
 * @param result    result of the reconciliation
 * @param visitId   visit of the digital logbook (may be null)
 * @param reviewTaskId task of the manual review queue (may be null)
 * @param reason    explanation in Spanish
 * @param idempotent true when the event had already been ingested
 */
public record GateEventResponse(
        UUID id,
        UUID eventId,
        String result,
        UUID visitId,
        UUID reviewTaskId,
        String reason,
        boolean idempotent) {
}
