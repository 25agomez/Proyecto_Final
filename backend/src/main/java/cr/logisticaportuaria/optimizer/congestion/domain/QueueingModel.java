package cr.logisticaportuaria.optimizer.congestion.domain;

/**
 * Closed-form queueing models used as the theoretical baseline of the congestion forecast.
 *
 * <p>All the rates are handled per minute internally, so the waits come out in minutes.
 * The models assume exponential inter-arrival times (M) and are used per gate, with the
 * number of lanes as the number of servers.
 */
public final class QueueingModel {

    /** Wait returned when the offered load exceeds the capacity (the queue is not stationary). */
    public static final double UNSTABLE_WAIT_MINUTES = 240.0;

    /** Queue length returned when the offered load exceeds the capacity. */
    public static final double UNSTABLE_QUEUE = 999.0;

    private static final double MAX_UTILIZATION = 0.999;

    private QueueingModel() {
    }

    /**
     * M/M/c model (Erlang C).
     *
     * @param arrivalsPerHour mean arrival rate
     * @param serviceMinutes  mean service time per lane, in minutes
     * @param servers         number of lanes
     * @return the expected wait, queue length and utilization
     */
    public static QueueingResult mmc(double arrivalsPerHour, double serviceMinutes, int servers) {
        return mgcAllenCunneen(arrivalsPerHour, serviceMinutes, servers, 1.0, 1.0);
    }

    /**
     * M/G/c approximation of Allen-Cunneen: the M/M/c wait scaled by {@code (ca2 + cs2) / 2}.
     *
     * @param arrivalsPerHour mean arrival rate
     * @param serviceMinutes  mean service time per lane, in minutes
     * @param servers         number of lanes
     * @param ca2             squared coefficient of variation of the arrivals (1 for Poisson)
     * @param cs2             squared coefficient of variation of the service times
     * @return the expected wait, queue length and utilization
     */
    public static QueueingResult mgcAllenCunneen(double arrivalsPerHour, double serviceMinutes, int servers,
            double ca2, double cs2) {
        if (servers <= 0) {
            throw new IllegalArgumentException("servers must be positive");
        }
        if (arrivalsPerHour <= 0.0 || serviceMinutes <= 0.0) {
            return new QueueingResult(0.0, 0.0, 0.0, 0.0, true, 1.0, 1.0);
        }
        double lambda = arrivalsPerHour / 60.0;
        double mu = 1.0 / serviceMinutes;
        double offeredLoad = lambda / mu;
        double utilization = offeredLoad / servers;
        if (utilization >= MAX_UTILIZATION) {
            return new QueueingResult(UNSTABLE_WAIT_MINUTES, UNSTABLE_QUEUE, 1.0, utilization, false, 0.0, 1.0);
        }
        double term = 1.0;
        double cumulative = 1.0;
        for (int k = 1; k < servers; k++) {
            term *= offeredLoad / k;
            cumulative += term;
        }
        double lastTerm = term * offeredLoad / servers;
        double tail = lastTerm / (1.0 - utilization);
        double probabilityOfWait = tail / (cumulative + tail);
        double decay = servers * mu - lambda;
        double scale = Math.max(1.0, (ca2 + cs2) / 2.0);
        double wait = scale * probabilityOfWait / decay;
        double queue = scale * probabilityOfWait * utilization / (1.0 - utilization);
        return new QueueingResult(wait, queue, probabilityOfWait, utilization, true, decay, scale);
    }

    /**
     * Quantile of the unconditional wait distribution.
     *
     * <p>For M/M/c the tail is exponential: {@code P(W > t) = C * exp(-(c*mu - lambda) * t)}.
     * For M/G/c the same shape scaled by the Allen-Cunneen factor is used (documented
     * approximation, not an exact result).
     *
     * @param result   queueing result of a stable model
     * @param quantile 0..1
     * @return the wait in minutes that is not exceeded with the given probability
     */
    public static double waitQuantile(QueueingResult result, double quantile) {
        if (quantile <= 0.0 || quantile >= 1.0) {
            throw new IllegalArgumentException("quantile must be in (0, 1)");
        }
        if (!result.stable()) {
            return UNSTABLE_WAIT_MINUTES;
        }
        if (result.probabilityOfWait() <= 0.0 || result.decayRatePerMinute() <= 0.0) {
            return 0.0;
        }
        double tail = 1.0 - quantile;
        if (tail >= result.probabilityOfWait()) {
            return 0.0;
        }
        double t = -Math.log(tail / result.probabilityOfWait()) / result.decayRatePerMinute();
        return Math.min(UNSTABLE_WAIT_MINUTES, result.scale() * t);
    }

    /**
     * Wait expected {@code horizonMinutes} from now, combining the steady state with the
     * backlog that the current queue still has to drain.
     *
     * <p>Documented approximation: the trucks already waiting plus the arrivals expected in
     * the horizon are served at full capacity; whatever remains is divided by the capacity
     * and added to the stationary wait.
     *
     * @param currentQueue    trucks already waiting at the gate
     * @param arrivalsPerHour arrivals expected during the horizon
     * @param serviceMinutes  mean service time per lane, in minutes
     * @param servers         number of lanes
     * @param horizonMinutes  horizon of the forecast
     * @return the expected wait in minutes, capped at {@link #UNSTABLE_WAIT_MINUTES}
     */
    public static double waitAtHorizon(double currentQueue, double arrivalsPerHour, double serviceMinutes,
            int servers, double horizonMinutes) {
        QueueingResult steady = mmc(arrivalsPerHour, serviceMinutes, servers);
        double lambda = arrivalsPerHour / 60.0;
        double capacityPerMinute = servers / serviceMinutes;
        double backlog = Math.max(0.0, currentQueue + lambda * horizonMinutes - capacityPerMinute * horizonMinutes);
        double residualWait = backlog / capacityPerMinute;
        if (!steady.stable()) {
            // a saturated gate has no stationary wait: only the backlog that cannot be drained
            // within the horizon is used (fluid approximation, documented in the ADR)
            return Math.min(UNSTABLE_WAIT_MINUTES, residualWait);
        }
        return Math.min(UNSTABLE_WAIT_MINUTES, steady.waitMinutes() + residualWait);
    }
}
