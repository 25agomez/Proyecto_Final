package cr.logisticaportuaria.fleet.application;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Counters and latency of the telemetry ingestion.
 *
 * <p>The counters are the source of the "sent vs. persisted" comparison of the load test and
 * of the {@code GET /api/v1/fleet/kpis} endpoint: every message received increments
 * {@code received} and exactly one of the outcome counters, so
 * {@code received = persisted + duplicates + outOfOrder + impossibleJumps + invalid + unknownVehicle}
 * when no message is lost.
 */
@Component
public class FleetMetrics {

    private static final int LATENCY_SAMPLES = 4096;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong persisted = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong outOfOrder = new AtomicLong();
    private final AtomicLong impossibleJumps = new AtomicLong();
    private final AtomicLong invalid = new AtomicLong();
    private final AtomicLong unknownVehicle = new AtomicLong();
    private final AtomicLong alerts = new AtomicLong();
    private final AtomicLong broadcasts = new AtomicLong();

    private final long[] latencyNanos = new long[LATENCY_SAMPLES];
    private final AtomicInteger latencyIndex = new AtomicInteger();
    private final AtomicLong latencyTotal = new AtomicLong();

    /**
     * Records one received message.
     */
    public void received() {
        received.incrementAndGet();
    }

    /**
     * Records one persisted position.
     */
    public void persisted() {
        persisted.incrementAndGet();
    }

    /**
     * Records one duplicate message.
     */
    public void duplicate() {
        duplicates.incrementAndGet();
    }

    /**
     * Records one out-of-order message.
     */
    public void outOfOrder() {
        outOfOrder.incrementAndGet();
    }

    /**
     * Records one rejected jump.
     */
    public void impossibleJump() {
        impossibleJumps.incrementAndGet();
    }

    /**
     * Records one invalid message.
     */
    public void invalid() {
        invalid.incrementAndGet();
    }

    /**
     * Records one message of an unknown vehicle.
     */
    public void unknownVehicle() {
        unknownVehicle.incrementAndGet();
    }

    /**
     * Records one emitted alert.
     */
    public void alert() {
        alerts.incrementAndGet();
    }

    /**
     * Records one broadcast flush.
     */
    public void broadcast() {
        broadcasts.incrementAndGet();
    }

    /**
     * Records the latency between the reception of a message and its WebSocket broadcast.
     *
     * @param nanos latency in nanoseconds
     */
    public void latency(long nanos) {
        int index = latencyIndex.getAndIncrement();
        latencyNanos[Math.floorMod(index, LATENCY_SAMPLES)] = nanos;
        latencyTotal.incrementAndGet();
    }

    /**
     * @return current snapshot of the counters and latency percentiles
     */
    public Snapshot snapshot() {
        long[] copy = Arrays.copyOf(latencyNanos, LATENCY_SAMPLES);
        int used = (int) Math.min(latencyTotal.get(), LATENCY_SAMPLES);
        long[] values = Arrays.stream(copy, 0, used).filter(value -> value > 0).sorted().toArray();
        double p50 = percentile(values, 0.50);
        double p95 = percentile(values, 0.95);
        return new Snapshot(received.get(), persisted.get(), duplicates.get(), outOfOrder.get(),
                impossibleJumps.get(), invalid.get(), unknownVehicle.get(), alerts.get(),
                broadcasts.get(), p50, p95);
    }

    private static double percentile(long[] sortedNanos, double percentile) {
        if (sortedNanos.length == 0) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sortedNanos.length) - 1;
        return sortedNanos[Math.max(0, Math.min(sortedNanos.length - 1, index))] / 1_000_000.0;
    }

    /**
     * Immutable snapshot of the counters.
     *
     * @param received        messages received
     * @param persisted       positions persisted
     * @param duplicates      duplicates ignored
     * @param outOfOrder      out-of-order messages ignored
     * @param impossibleJumps impossible jumps rejected
     * @param invalid         invalid messages rejected
     * @param unknownVehicles messages of unknown vehicles
     * @param alerts          alerts emitted
     * @param broadcasts      broadcast flushes
     * @param latencyP50Ms    p50 of the reception to WebSocket latency, in milliseconds
     * @param latencyP95Ms    p95 of the reception to WebSocket latency, in milliseconds
     */
    public record Snapshot(
            long received,
            long persisted,
            long duplicates,
            long outOfOrder,
            long impossibleJumps,
            long invalid,
            long unknownVehicles,
            long alerts,
            long broadcasts,
            double latencyP50Ms,
            double latencyP95Ms) {
    }
}
