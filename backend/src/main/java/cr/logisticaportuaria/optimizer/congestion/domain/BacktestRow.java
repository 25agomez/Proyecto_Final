package cr.logisticaportuaria.optimizer.congestion.domain;

import java.time.Instant;

/**
 * One row of the temporal hold-out, kept for the reproducible report.
 *
 * @param bucket  start of the 15-minute bucket
 * @param actual  mean wait observed in the bucket (minutes)
 * @param queue   prediction of the queueing model with the seasonal profile (minutes)
 * @param smoothed prediction of the queueing model with the Holt-Winters arrival rate (minutes)
 * @param hybrid  prediction of the queueing + boosting model (minutes)
 * @param naive   prediction of the naive baseline (previous bucket)
 * @param p10     lower bound of the model in use
 * @param p90     upper bound of the model in use
 */
public record BacktestRow(Instant bucket, double actual, double queue, double smoothed, double hybrid, double naive,
        double p10, double p90) {
}
