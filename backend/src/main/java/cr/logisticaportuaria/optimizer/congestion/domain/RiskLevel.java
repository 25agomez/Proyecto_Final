package cr.logisticaportuaria.optimizer.congestion.domain;

/** Risk level of a congestion forecast. */
public enum RiskLevel {

    /** Expected wait below the medium threshold. */
    BAJO,

    /** Expected wait between the medium and the high threshold. */
    MEDIO,

    /** Expected wait above the high threshold. */
    ALTO
}
