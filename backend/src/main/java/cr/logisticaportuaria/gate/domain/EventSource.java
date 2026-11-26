package cr.logisticaportuaria.gate.domain;

/**
 * Origin of a gate event ({@code gate_event.source}).
 */
public enum EventSource {

    /** Published by the edge service. */
    EDGE,

    /** Registered by an operator in the manual review queue. */
    MANUAL,

    /** Created through the API. */
    API
}
