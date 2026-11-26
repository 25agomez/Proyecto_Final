package cr.logisticaportuaria.gate.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to resolve a task of the manual review queue.
 *
 * @param resolution    APROBADO, CORREGIDO or RECHAZADO
 * @param correctedText text confirmed or corrected by the operator
 * @param reason        reason of the resolution
 */
public record ResolveReviewRequest(
        @NotNull String resolution,
        @Size(max = 64) String correctedText,
        String reason) {
}
