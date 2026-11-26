package cr.logisticaportuaria.gate.domain;

/**
 * Status of a task of the manual review queue ({@code review_task.status}).
 */
public enum ReviewStatus {

    /** Waiting for an operator. */
    PENDIENTE,

    /** The operator confirmed the reading. */
    APROBADO,

    /** The operator corrected the reading. */
    CORREGIDO,

    /** The operator rejected the reading. */
    RECHAZADO
}
