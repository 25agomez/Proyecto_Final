package cr.logisticaportuaria.yard.domain;

/**
 * Status of a container ({@code container.status}).
 */
public enum ContainerStatus {

    /** The container is on its way to the yard. */
    IN_TRANSIT,

    /** The container is inside the yard. */
    IN_YARD,

    /** The container is reserved for a client. */
    RESERVED,

    /** The container is blocked at the gate. */
    GATE_HOLD,

    /** The container already left the yard. */
    OUT,

    /** The status could not be determined. */
    UNKNOWN
}
