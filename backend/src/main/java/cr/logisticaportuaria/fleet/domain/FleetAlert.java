package cr.logisticaportuaria.fleet.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Alert or zone event of the fleet.
 *
 * @param type        kind of alert
 * @param vehicleId   identifier of the vehicle
 * @param vehicleCode code shown to the operator
 * @param yardId      identifier of the yard
 * @param at          instant of the event
 * @param message     explanation in Spanish (es-CR)
 * @param x           position where the event was detected, in the local frame
 * @param y           position where the event was detected, in the local frame
 */
public record FleetAlert(
        AlertType type,
        UUID vehicleId,
        String vehicleCode,
        UUID yardId,
        Instant at,
        String message,
        double x,
        double y) {

    /**
     * @return severity of the alert
     */
    public AlertType.Severity severity() {
        return type.severity();
    }

    /**
     * @param suffix extra text appended to the message
     * @return a copy with the message extended
     */
    public FleetAlert withMessageSuffix(String suffix) {
        return new FleetAlert(type, vehicleId, vehicleCode, yardId, at, message + suffix, x, y);
    }
}
