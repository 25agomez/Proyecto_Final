package cr.logisticaportuaria.gate.domain;

/**
 * Kind of identifier that was read at the gate.
 */
public enum ReadingKind {

    /** Plate of a truck. */
    PLATE,

    /** BIC code of a container (ISO 6346). */
    CONTAINER
}
