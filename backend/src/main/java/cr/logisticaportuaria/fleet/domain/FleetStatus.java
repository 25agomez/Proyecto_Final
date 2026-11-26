package cr.logisticaportuaria.fleet.domain;

/**
 * Operational status of a vehicle, matching the CHECK of {@code vehicle.status} and the
 * payload of the telemetry.
 */
public enum FleetStatus {
    LIBRE,
    EN_MOVIMIENTO,
    CARGANDO,
    DESCARGANDO,
    AVERIA;

    /**
     * @return whether the status counts as productive time
     */
    public boolean isProductive() {
        return this == EN_MOVIMIENTO || this == CARGANDO || this == DESCARGANDO;
    }

    /**
     * @return whether the vehicle can receive a new work order
     */
    public boolean isFree() {
        return this == LIBRE;
    }

    /**
     * @param value raw value of the payload
     * @return the status, or {@code null} when it is not valid
     */
    public static FleetStatus parse(String value) {
        if (value == null) {
            return null;
        }
        for (FleetStatus status : values()) {
            if (status.name().equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        return null;
    }
}
