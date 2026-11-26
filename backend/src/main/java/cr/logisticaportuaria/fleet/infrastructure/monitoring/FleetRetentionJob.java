package cr.logisticaportuaria.fleet.infrastructure.monitoring;

import cr.logisticaportuaria.fleet.application.FleetProperties;
import cr.logisticaportuaria.fleet.application.FleetTelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly job that downsamples the old positions and applies the retention.
 *
 * <p>Positions older than {@code app.fleet.downsample-after-hours} (24 h) keep one point
 * every {@code app.fleet.downsample-seconds} (10 s), and positions older than
 * {@code app.fleet.retention-days} (30 days) are deleted. The raw series of the last 24 h is
 * never touched, so the dashboard and the KPIs keep the full resolution.
 */
@Component
@ConditionalOnProperty(name = "app.fleet.scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class FleetRetentionJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(FleetRetentionJob.class);

    private final FleetTelemetryService telemetryService;
    private final FleetProperties properties;

    /**
     * Constructor.
     *
     * @param telemetryService service of telemetry
     * @param properties       configuration of the module
     */
    public FleetRetentionJob(FleetTelemetryService telemetryService, FleetProperties properties) {
        this.telemetryService = telemetryService;
        this.properties = properties;
    }

    /**
     * Runs every hour at minute 17.
     */
    @Scheduled(cron = "0 17 * * * *")
    public void run() {
        FleetTelemetryService.RetentionReport report =
                telemetryService.applyRetention(java.time.Instant.now());
        LOGGER.info("Retencion de flota: {} puntos eliminados por antiguedad ({} dias), "
                        + "{} puntos eliminados por downsampling (1/{} s tras {} h).",
                report.purged(), properties.getRetentionDays(), report.downsampled(),
                properties.getDownsampleSeconds(), properties.getDownsampleAfterHours());
    }
}
