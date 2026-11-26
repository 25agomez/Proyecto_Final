package cr.logisticaportuaria.gate.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Reading of the gate as it is shown in the listing of the API.
 *
 * <p>It extends the shape of {@code GateEventResponse} of the OpenAPI
 * contract with the data of the reading itself.
 *
 * @param id             identifier of the stored event
 * @param eventId        identifier provided by the edge
 * @param gateId         gate of the reading
 * @param kind           PLATE or CONTAINER
 * @param rawText        text as it was read
 * @param normalizedText normalized text
 * @param confidence     confidence of the recognition
 * @param isoValid       result of the ISO 6346 validation
 * @param corrected      whether the edge corrected the reading
 * @param observedAt     instant of the reading
 * @param source         origin of the event
 * @param result         result of the reconciliation
 * @param visitId        visit of the digital logbook (may be null)
 * @param reason         explanation in Spanish
 */
public record GateEventSummary(
        UUID id,
        UUID eventId,
        UUID gateId,
        String kind,
        String rawText,
        String normalizedText,
        BigDecimal confidence,
        Boolean isoValid,
        boolean corrected,
        Instant observedAt,
        String source,
        String result,
        UUID visitId,
        String reason) {
}
