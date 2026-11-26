package cr.logisticaportuaria.gate.domain;

/**
 * States of the digital logbook ({@code visit_log.state}).
 */
public enum VisitState {

    /** The truck arrived at the gate. */
    ENTRADA_PUERTA,

    /** The reading was reconciled and the visit was accepted. */
    VALIDADO,

    /** A slot (and the andén) was assigned to the visit. */
    ASIGNADO,

    /** The container is being moved in the yard. */
    EN_PROCESO,

    /** The truck left the yard. */
    SALIDA_PUERTA,

    /** The visit was cancelled. */
    CANCELADO
}
