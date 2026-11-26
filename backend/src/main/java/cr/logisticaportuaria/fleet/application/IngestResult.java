package cr.logisticaportuaria.fleet.application;

/**
 * Result of the ingestion of one telemetry message.
 */
public enum IngestResult {
    ACCEPTED,
    DUPLICATE,
    OUT_OF_ORDER,
    IMPOSSIBLE_JUMP,
    INVALID,
    UNKNOWN_VEHICLE
}
