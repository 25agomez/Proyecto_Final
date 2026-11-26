package cr.logisticaportuaria.optimizer.congestion.domain;

/**
 * Result of a queueing model for one gate.
 *
 * <p>The rates are expressed per minute so the wait is returned directly in minutes.
 *
 * @param waitMinutes        expected wait in the queue (minutes)
 * @param queueLength        expected number of trucks waiting
 * @param probabilityOfWait  Erlang C: probability that an arrival has to wait (0..1)
 * @param utilization        offered load divided by the number of servers (&rho;)
 * @param stable             false when &lambda; &ge; c&middot;&mu; (the queue grows without bound)
 * @param decayRatePerMinute c&middot;&mu; &minus; &lambda;, the rate of the wait distribution
 * @param scale              multiplier of the M/G/c approximation (1 for M/M/c)
 */
public record QueueingResult(
        double waitMinutes,
        double queueLength,
        double probabilityOfWait,
        double utilization,
        boolean stable,
        double decayRatePerMinute,
        double scale) {
}
