package cr.logisticaportuaria.yard.domain;

/**
 * Kind of a gate ({@code gate.kind}).
 */
public enum GateKind {

    /** Gate only for the entrance of trucks. */
    IN,

    /** Gate only for the exit of trucks. */
    OUT,

    /** Gate for both directions. */
    BOTH
}
