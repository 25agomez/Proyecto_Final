package cr.logisticaportuaria.optimizer.congestion.domain;

import java.util.List;

/**
 * Model fitted for one horizon of a gate (15, 30 or 60 minutes).
 *
 * <p>Each horizon has its own regressor because the state that predicts the wait of a truck
 * arriving in 15 minutes is not the same as the one that predicts the wait in 60 minutes: the
 * features are taken at the origin of the forecast, the calendar features at the horizon and
 * the wait baseline is {@link QueueingModel#waitAtHorizon}.
 *
 * @param horizonMin      horizon in minutes
 * @param regressor       boosting correction of the queueing baseline
 * @param correctionUsed  whether the correction improved the hold-out MAE
 * @param residualP10     p10 of the residual of the active model (minutes)
 * @param residualP90     p90 of the residual of the active model (minutes)
 * @param queueMetrics    hold-out metrics of the queueing model with the seasonal profile
 * @param smoothedMetrics hold-out metrics of the queueing model with the Holt-Winters rate
 * @param hybridMetrics   hold-out metrics of the hybrid model
 * @param naiveMetrics    hold-out metrics of the naive baseline
 * @param backtest        rows of the hold-out, oldest first
 */
public record HorizonModel(
        int horizonMin,
        GradientBoostingRegressor regressor,
        boolean correctionUsed,
        double residualP10,
        double residualP90,
        ModelMetrics queueMetrics,
        ModelMetrics smoothedMetrics,
        ModelMetrics hybridMetrics,
        ModelMetrics naiveMetrics,
        List<BacktestRow> backtest) {

    /**
     * @return {@code HIBRIDO} when the boosting correction is used, {@code COLAS} otherwise
     */
    public String modelLabel() {
        return correctionUsed ? "HIBRIDO" : "COLAS";
    }

    /**
     * @return the metrics of the model actually used by the forecast
     */
    public ModelMetrics activeMetrics() {
        return correctionUsed ? hybridMetrics : queueMetrics;
    }
}
