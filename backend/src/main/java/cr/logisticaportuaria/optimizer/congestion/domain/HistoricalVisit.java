package cr.logisticaportuaria.optimizer.congestion.domain;

import java.time.Instant;

/**
 * One closed visit of a gate, as used by the congestion model.
 *
 * @param enteredAt      arrival to the gate
 * @param exitedAt       exit from the gate (end of the visit)
 * @param waitMinutes    time between the arrival and the start of the service
 * @param serviceMinutes time the truck occupied the gate once served
 */
public record HistoricalVisit(Instant enteredAt, Instant exitedAt, double waitMinutes, double serviceMinutes) {

    /**
     * Whether the visit was still in progress at the given instant.
     *
     * @param instant any instant
     * @return true when the truck was inside the gate
     */
    public boolean covers(Instant instant) {
        return !instant.isBefore(enteredAt) && (exitedAt == null || instant.isBefore(exitedAt));
    }
}
