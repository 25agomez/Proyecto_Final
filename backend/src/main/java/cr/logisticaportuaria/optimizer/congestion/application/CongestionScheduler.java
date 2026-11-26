package cr.logisticaportuaria.optimizer.congestion.application;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled recomputation of the congestion forecasts.
 *
 * <p>Runs every {@code app.congestion.refresh-ms} (5 minutes by default), persists the three
 * horizons of every active gate and publishes the ALTO alerts. It can be disabled with
 * {@code app.congestion.scheduling-enabled=false} (tests and offline demos).
 */
@Component
public class CongestionScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CongestionScheduler.class);

    private final CongestionService service;
    private final CongestionProperties properties;

    /**
     * Constructor.
     *
     * @param service    service of the congestion forecast
     * @param properties configuration of the module
     */
    public CongestionScheduler(CongestionService service, CongestionProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /** Recomputes and persists the forecasts of every active gate. */
    @Scheduled(initialDelayString = "${app.congestion.initial-delay-ms:60000}",
            fixedDelayString = "${app.congestion.refresh-ms:300000}")
    public void refresh() {
        if (!properties.isSchedulingEnabled()) {
            return;
        }
        try {
            service.refreshAll(Instant.now());
        } catch (RuntimeException exception) {
            LOGGER.error("Falló el recálculo programado de congestión: {}", exception.getMessage(), exception);
        }
    }
}
