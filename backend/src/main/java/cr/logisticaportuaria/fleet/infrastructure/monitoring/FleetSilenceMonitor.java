package cr.logisticaportuaria.fleet.infrastructure.monitoring;


import cr.logisticaportuaria.fleet.application.FleetTelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic check of the vehicles that stopped sending telemetry.
 */
@Component
@ConditionalOnProperty(name = "app.fleet.scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class FleetSilenceMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(FleetSilenceMonitor.class);

    private final FleetTelemetryService telemetryService;

    /**
     * Constructor.
     *
     * @param telemetryService service of telemetry
     */
    public FleetSilenceMonitor(FleetTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    /**
     * Checks the silence of every vehicle every 10 s.
     */
    @Scheduled(fixedDelay = 10_000L, initialDelay = 10_000L)
    public void check() {
        int raised = telemetryService.checkSilence(java.time.Instant.now());
        if (raised > 0) {
            LOGGER.warn("{} alertas de SIN_SENAL emitidas.", raised);
        }
    }
}
