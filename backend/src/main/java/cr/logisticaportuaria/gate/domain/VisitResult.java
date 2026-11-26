package cr.logisticaportuaria.gate.domain;

/**
 * Result of the reconciliation of a reading ({@code visit_log.result}).
 */
public enum VisitResult {

    /** The reading has not been reconciled yet. */
    PENDIENTE,

    /** The reading was reconciled automatically. */
    AUTO_APROBADO,

    /** The reading needs the intervention of an operator. */
    REQUIERE_REVISION,

    /** The reading was rejected (blocked container, hold, ...). */
    RECHAZADO
}
