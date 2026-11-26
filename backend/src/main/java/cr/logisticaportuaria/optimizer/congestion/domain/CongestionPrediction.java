package cr.logisticaportuaria.optimizer.congestion.domain;

import java.util.List;
import java.util.UUID;

/**
 * Forecast of the congestion of one gate for one horizon.
 *
 * @param gateId          identifier of the gate
 * @param gateCode        human readable code of the gate
 * @param horizonMin      horizon of the forecast, in minutes
 * @param expectedWaitMin expected wait of a truck arriving at the horizon
 * @param p10             lower bound of the 80 % interval
 * @param p90             upper bound of the 80 % interval
 * @param queueLength     trucks expected to be waiting at the horizon
 * @param riskLevel       risk band derived from the expected wait
 * @param confidence      confidence derived from the available history
 * @param model           model that produced the point forecast
 * @param arrivalsPerHour arrivals per hour assumed by the model
 * @param utilization     offered load divided by the capacity
 * @param drivers         interpretable factors, in Spanish, at least two
 * @param recommendations actionable suggestions, in Spanish
 */
public record CongestionPrediction(
        UUID gateId,
        String gateCode,
        int horizonMin,
        double expectedWaitMin,
        double p10,
        double p90,
        double queueLength,
        RiskLevel riskLevel,
        ConfidenceLevel confidence,
        String model,
        double arrivalsPerHour,
        double utilization,
        List<String> drivers,
        List<String> recommendations) {

    /** @return the point forecast rounded to one decimal, for the API */
    public double roundedWait() {
        return Math.round(expectedWaitMin * 10.0) / 10.0;
    }
}
