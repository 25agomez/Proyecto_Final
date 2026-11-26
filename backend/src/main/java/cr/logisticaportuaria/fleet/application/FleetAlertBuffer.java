package cr.logisticaportuaria.fleet.application;

import cr.logisticaportuaria.fleet.domain.FleetAlert;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Bounded in-memory log of the recent alerts, used by the KPI endpoint.
 *
 * <p>The schema of P1 has no alert table and the fleet module must not change the migrations
 * of the architect (MEMORY.md §11), so the alerts are kept in memory only. They are
 * informational; the durable evidence is the position series (ADR-014).
 */
@Component
public class FleetAlertBuffer {

    private final Deque<FleetAlert> alerts = new ArrayDeque<>();
    private final Map<String, AtomicInteger> perType = new ConcurrentHashMap<>();
    private final int capacity;

    /**
     * Constructor.
     *
     * @param properties configuration of the module
     */
    public FleetAlertBuffer(FleetProperties properties) {
        this.capacity = Math.max(1, properties.getAlertBufferSize());
    }

    /**
     * @param alert alert to keep
     */
    public synchronized void add(FleetAlert alert) {
        alerts.addLast(alert);
        perType.computeIfAbsent(alert.type().name(), key -> new AtomicInteger()).incrementAndGet();
        while (alerts.size() > capacity) {
            FleetAlert removed = alerts.removeFirst();
            AtomicInteger counter = perType.get(removed.type().name());
            if (counter != null) {
                counter.decrementAndGet();
            }
        }
    }

    /**
     * @param since inclusive instant
     * @return alerts emitted since the given instant
     */
    public synchronized List<FleetAlert> since(java.time.Instant since) {
        List<FleetAlert> result = new ArrayList<>();
        for (FleetAlert alert : alerts) {
            if (!alert.at().isBefore(since)) {
                result.add(alert);
            }
        }
        return result;
    }

    /**
     * @param since inclusive instant
     * @return count of alerts per type since the given instant
     */
    public java.util.Map<String, Long> countByType(java.time.Instant since) {
        return since(since).stream().collect(java.util.stream.Collectors.groupingBy(
                alert -> alert.type().name(), java.util.stream.Collectors.counting()));
    }

    /**
     * @return total count of alerts kept
     */
    public synchronized int size() {
        return alerts.size();
    }

    /**
     * Clears the buffer (tests).
     */
    public synchronized void clear() {
        alerts.clear();
        perType.clear();
    }
}
