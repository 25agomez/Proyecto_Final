package cr.logisticaportuaria.optimizer.congestion.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response of {@code GET /api/v1/congestion/forecast}.
 *
 * @param gateId          gate
 * @param gateCode        code of the gate
 * @param horizonMin      horizon in minutes (15, 30 or 60)
 * @param expectedWaitMin expected wait in minutes
 * @param p10             lower bound of the 80 % interval
 * @param p90             upper bound of the 80 % interval
 * @param queueLength     trucks expected to be waiting at the horizon
 * @param riskLevel       BAJO, MEDIO or ALTO
 * @param confidence      BAJA, MEDIA or ALTA
 * @param model           COLAS or HIBRIDO
 * @param arrivalsPerHour arrivals per hour assumed by the model
 * @param utilization     offered load divided by the capacity (0..1)
 * @param drivers         interpretable factors, in Spanish (at least two)
 * @param recommendations actionable suggestions, in Spanish
 * @param computedAt      instant of the calculation
 * @param quality         measured quality of the model
 */
public record CongestionForecastResponse(
        UUID gateId,
        String gateCode,
        int horizonMin,
        double expectedWaitMin,
        double p10,
        double p90,
        double queueLength,
        String riskLevel,
        String confidence,
        String model,
        double arrivalsPerHour,
        double utilization,
        List<String> drivers,
        List<String> recommendations,
        Instant computedAt,
        Quality quality) {

    /**
     * Measured quality of the model, on the temporal hold-out.
     *
     * @param queueMae    MAE of the queueing model with the seasonal profile (minutes)
     * @param smoothedMae MAE of the queueing model with the Holt-Winters rate (minutes)
     * @param hybridMae  MAE of the queueing + boosting model (minutes)
     * @param naiveMae   MAE of the naive baseline (minutes)
     * @param rmse       RMSE of the model in use (minutes)
     * @param mape       MAPE of the model in use (percent)
     * @param coverage80 share of rows inside the p10-p90 band
     * @param samples    number of training buckets
     * @param active     model in use ({@code COLAS} or {@code HIBRIDO})
     */
    public record Quality(double queueMae, double smoothedMae, double hybridMae, double naiveMae, double rmse,
            double mape, double coverage80, int samples, String active) {
    }
}
