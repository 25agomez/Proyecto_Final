package cr.logisticaportuaria.fleet.infrastructure.websocket;

import cr.logisticaportuaria.fleet.application.FleetMetrics;
import cr.logisticaportuaria.fleet.application.FleetProperties;
import cr.logisticaportuaria.fleet.application.dto.FleetPositionMessage;
import cr.logisticaportuaria.fleet.domain.FleetAlert;
import cr.logisticaportuaria.fleet.domain.VehicleState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * WebSocket broadcaster of the fleet.
 *
 * <p>Positions are accumulated in memory and flushed every
 * {@code app.fleet.broadcast-interval-ms} (1 s by default) to
 * {@code /topic/yards/{yardId}/fleet}, so a burst of telemetry does not flood the clients.
 * Alerts are sent immediately to {@code /topic/yards/{yardId}/alerts} (ADR-014).
 *
 * <p>The flush measures the latency between the reception of the message and the call to
 * {@code convertAndSend}; the percentiles are part of {@code GET /fleet/kpis}.
 */
@Component
public class FleetBroadcaster {

    private final SimpMessagingTemplate template;
    private final FleetMetrics metrics;
    private final int intervalMs;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    /**
     * Constructor.
     *
     * @param template   template of the simple broker
     * @param metrics    counters of the module
     * @param properties configuration of the module
     */
    public FleetBroadcaster(SimpMessagingTemplate template, FleetMetrics metrics,
            FleetProperties properties) {
        this.template = template;
        this.metrics = metrics;
        this.intervalMs = properties.getBroadcastIntervalMs();
    }

    /**
     * @return interval of the flush, in milliseconds
     */
    public int intervalMs() {
        return intervalMs;
    }

    /**
     * Queues a position; only the newest one of each vehicle is kept.
     *
     * @param state           state to broadcast
     * @param receivedAtNanos {@code System.nanoTime()} of the reception
     */
    public void queue(VehicleState state, long receivedAtNanos) {
        pending.compute(state.vehicleId(), (id, previous) -> previous == null
                || !state.at().isBefore(previous.state.at())
                        ? new Pending(state, receivedAtNanos) : previous);
    }

    /**
     * Sends an alert immediately.
     *
     * @param alert alert to broadcast
     */
    public void broadcastAlert(FleetAlert alert) {
        template.convertAndSend("/topic/yards/" + alert.yardId() + "/alerts", alert);
    }

    /**
     * Sends the accumulated positions, grouped by yard.
     *
     * @return number of messages sent
     */
    public int flush() {
        if (pending.isEmpty()) {
            return 0;
        }
        Map<UUID, List<Pending>> byYard = new HashMap<>();
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            Pending value = pending.remove(entry.getKey());
            if (value != null) {
                byYard.computeIfAbsent(value.state.yardId(), key -> new ArrayList<>()).add(value);
            }
        }
        long now = System.nanoTime();
        int sent = 0;
        for (Map.Entry<UUID, List<Pending>> entry : byYard.entrySet()) {
            List<FleetPositionMessage> messages = entry.getValue().stream()
                    .map(item -> FleetPositionMessage.from(item.state))
                    .toList();
            template.convertAndSend("/topic/yards/" + entry.getKey() + "/fleet", messages);
            for (Pending item : entry.getValue()) {
                metrics.latency(now - item.receivedAtNanos);
            }
            metrics.broadcast();
            sent += messages.size();
        }
        return sent;
    }

    /**
     * @return number of positions waiting for the next flush
     */
    public int pendingCount() {
        return pending.size();
    }

    private record Pending(VehicleState state, long receivedAtNanos) {
    }
}
