package cr.logisticaportuaria.gate.domain;

import cr.logisticaportuaria.shared.domain.Direction;
import java.util.UUID;

/**
 * Result of the reconciliation of one reading.
 *
 * @param result        outcome of the reconciliation
 * @param reason        explanation in Spanish for the operator
 * @param direction     direction of the visit (null when it could not be determined)
 * @param appointmentId appointment that matched the reading (may be null)
 * @param truckId       truck of the matched appointment (may be null)
 * @param containerId   container of the matched BIC code (may be null)
 * @param plate         normalized plate (may be null)
 * @param containerCode normalized BIC code (may be null)
 */
public record ReconciliationDecision(
        VisitResult result,
        String reason,
        Direction direction,
        UUID appointmentId,
        UUID truckId,
        UUID containerId,
        String plate,
        String containerCode) {

    /**
     * @return true when the reading was accepted without human intervention
     */
    public boolean approved() {
        return result == VisitResult.AUTO_APROBADO;
    }

    /**
     * @return true when the reading must be reviewed by an operator
     */
    public boolean requiresReview() {
        return result == VisitResult.REQUIERE_REVISION;
    }
}
