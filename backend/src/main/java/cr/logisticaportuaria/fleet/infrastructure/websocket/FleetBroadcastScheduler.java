package cr.logisticaportuaria.fleet.infrastructure.websocket;

import cr.logisticaportuaria.fleet.application.FleetProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the aggregated fleet positions every {@code app.fleet.broadcast-interval-ms}.
 *
 * <p>Separated from {@link FleetBroadcaster} so tests can call {@code flush()} directly with
 * the scheduler disabled ({@code app.fleet.scheduling-enabled=false}).
 */
@Component
@ConditionalOnProperty(name = "app.fleet.scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class FleetBroadcastScheduler {

    private final FleetBroadcaster broadcaster;
    private final FleetProperties properties;

    /**
     * Constructor.
     *
     * @param broadcaster broadcaster of the fleet
     * @param properties  configuration of the module
     */
    public FleetBroadcastScheduler(FleetBroadcaster broadcaster, FleetProperties properties) {
        this.broadcaster = broadcaster;
        this.properties = properties;
    }

    /**
     * Flushes the pending positions.
     */
    @Scheduled(fixedDelayString = "${app.fleet.broadcast-interval-ms:1000}")
    public void flush() {
        if (properties.isSchedulingEnabled()) {
            broadcaster.flush();
        }
    }
}
