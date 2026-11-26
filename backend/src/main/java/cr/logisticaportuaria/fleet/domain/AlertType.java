package cr.logisticaportuaria.fleet.domain;

/**
 * Kind of fleet alert, with the severity used by the dashboard.
 */
public enum AlertType {

    /** The vehicle exceeded the configured speed limit. */
    EXCESO_VELOCIDAD(Severity.ALTA),

    /** The vehicle stayed stopped for longer than the configured idle time. */
    RALENTI(Severity.MEDIA),

    /** The vehicle entered a forbidden zone. */
    ZONA_PROHIBIDA(Severity.ALTA),

    /** The battery dropped below the configured threshold. */
    BATERIA_BAJA(Severity.MEDIA),

    /** The vehicle stopped sending telemetry. */
    SIN_SENAL(Severity.ALTA),

    /** The distance between two consecutive positions is physically impossible. */
    SALTO_GPS(Severity.MEDIA),

    /** The vehicle reported a breakdown. */
    AVERIA(Severity.ALTA),

    /** Informative event: the vehicle entered a zone. */
    ENTRADA_ZONA(Severity.INFO),

    /** Informative event: the vehicle left a zone. */
    SALIDA_ZONA(Severity.INFO);

    /**
     * Severity of an alert.
     */
    public enum Severity {
        INFO,
        MEDIA,
        ALTA
    }

    private final Severity severity;

    AlertType(Severity severity) {
        this.severity = severity;
    }

    /**
     * @return severity of the alert
     */
    public Severity severity() {
        return severity;
    }
}
