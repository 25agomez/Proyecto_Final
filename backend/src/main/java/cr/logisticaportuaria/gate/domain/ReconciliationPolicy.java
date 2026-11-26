package cr.logisticaportuaria.gate.domain;

/**
 * Parameters of the reconciliation, configurable per installation.
 *
 * @param minConfidence              minimum confidence accepted for an automatic approval
 * @param defaultWindowMinutes       window (in minutes) used when the appointment does not define one
 * @param requireAppointmentForPlates whether a plate without a valid appointment must be reviewed
 * @param blockOnGateHold            whether a container under hold must be rejected
 */
public record ReconciliationPolicy(
        double minConfidence,
        int defaultWindowMinutes,
        boolean requireAppointmentForPlates,
        boolean blockOnGateHold) {

    /** Policy used when nothing is configured. */
    public static final ReconciliationPolicy DEFAULT = new ReconciliationPolicy(0.75, 30, true, true);
}
