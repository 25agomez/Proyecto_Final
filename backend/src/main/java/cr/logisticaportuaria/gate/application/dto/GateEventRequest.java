package cr.logisticaportuaria.gate.application.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Reading received from the edge service. It is the exact body of
 * {@code edge-ocr/schemas/gate-event.schema.json}.
 *
 * @param eventId          identifier of the event (idempotency key)
 * @param yardId           yard of the reading (optional: it can be deduced from the gate)
 * @param gateId           gate of the reading
 * @param timestamp        instant of the reading (UTC)
 * @param kind             PLATE or CONTAINER
 * @param rawText          text as it was read
 * @param normalizedText   normalized text
 * @param confidence       confidence of the recognition (0..1)
 * @param isoValid         result of the ISO 6346 validation (containers only)
 * @param corrected        whether the edge corrected the reading
 * @param bbox             oriented bounding box of the reading
 * @param evidenceImagePath path of the evidence image
 * @param modelVersion     version of the models
 * @param latencyMs        latency of the processing in the edge
 */
public record GateEventRequest(
        @NotNull UUID eventId,
        UUID yardId,
        @NotNull UUID gateId,
        @NotNull Instant timestamp,
        @NotNull String kind,
        String rawText,
        String normalizedText,
        @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
        Boolean isoValid,
        boolean corrected,
        Map<String, Object> bbox,
        String evidenceImagePath,
        String modelVersion,
        @PositiveOrZero Integer latencyMs) {
}
