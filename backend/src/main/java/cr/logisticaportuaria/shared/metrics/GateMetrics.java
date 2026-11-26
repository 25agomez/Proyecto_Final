package cr.logisticaportuaria.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Own metrics of the gate module.
 *
 * <ul>
 *   <li>{@code gate.events.total}: readings ingested, tagged by kind and result;</li>
 *   <li>{@code gate.review.tasks}: tasks created and resolved in the manual queue;</li>
 *   <li>{@code gate.review.ratio}: percentage of the readings that needed a manual review;</li>
 *   <li>{@code gate.visit.wait} and {@code gate.visit.yard}: distribution of the waiting time at the
 *       gate and of the time inside the yard;</li>
 *   <li>{@code gate.events.per.minute}: rate of readings ingested.</li>
 * </ul>
 */
@Component
public class GateMetrics {

    private final Counter eventsTotal;
    private final Counter reviewTasksCreated;
    private final Counter reviewTasksResolved;
    private final Counter visitsClosed;
    private final Timer gateWait;
    private final Timer yardTime;
    private final AtomicLong eventsCount = new AtomicLong();
    private final AtomicLong reviewCount = new AtomicLong();
    private final java.time.Instant startedAt = java.time.Instant.now();

    /**
     * Constructor.
     *
     * @param registry registry of the meters
     */
    public GateMetrics(MeterRegistry registry) {
        this.eventsTotal = Counter.builder("gate.events.total")
                .description("Lecturas de puerta procesadas")
                .register(registry);
        this.reviewTasksCreated = Counter.builder("gate.review.tasks.created")
                .description("Tareas creadas en la cola de revisión manual")
                .register(registry);
        this.reviewTasksResolved = Counter.builder("gate.review.tasks.resolved")
                .description("Tareas resueltas de la cola de revisión manual")
                .register(registry);
        this.visitsClosed = Counter.builder("gate.visits.closed")
                .description("Visitas cerradas (salidas de puerta)")
                .register(registry);
        this.gateWait = Timer.builder("gate.visit.wait")
                .description("Espera en puerta (segundos)")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        this.yardTime = Timer.builder("gate.visit.yard")
                .description("Tiempo total en el patio (segundos)")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        registry.gauge("gate.review.ratio", this, GateMetrics::reviewRatio);
        registry.gauge("gate.events.per.minute", this, GateMetrics::eventsPerMinute);
    }

    /**
     * Registers an ingested reading.
     *
     * @param kind   kind of the reading
     * @param result result of the reconciliation
     */
    public void recordEvent(String kind, String result) {
        eventsTotal.increment();
        eventsCount.incrementAndGet();
        if ("REQUIERE_REVISION".equals(result)) {
            reviewCount.incrementAndGet();
        }
    }

    /**
     * Registers the creation of a task of the manual review queue.
     */
    public void recordReviewTaskCreated() {
        reviewTasksCreated.increment();
    }

    /**
     * Registers the resolution of a task of the manual review queue.
     */
    public void recordReviewTaskResolved() {
        reviewTasksResolved.increment();
    }

    /**
     * Registers the closing of a visit.
     *
     * @param waitSeconds waiting time at the gate
     * @param yardSeconds time inside the yard
     */
    public void recordVisitClosed(Integer waitSeconds, Integer yardSeconds) {
        visitsClosed.increment();
        if (waitSeconds != null) {
            gateWait.record(waitSeconds, java.util.concurrent.TimeUnit.SECONDS);
        }
        if (yardSeconds != null) {
            yardTime.record(yardSeconds, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /**
     * @return percentage of the readings that needed a manual review
     */
    public double reviewRatio() {
        long total = eventsCount.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) reviewCount.get() / total;
    }

    /**
     * @return readings ingested per minute since the start of the application
     */
    public double eventsPerMinute() {
        long total = eventsCount.get();
        long minutes = Math.max(1, java.time.Duration.between(startedAt, java.time.Instant.now()).toMinutes());
        return (double) total / minutes;
    }
}
