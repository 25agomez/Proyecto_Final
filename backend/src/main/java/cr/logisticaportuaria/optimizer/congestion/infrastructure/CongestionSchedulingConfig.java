package cr.logisticaportuaria.optimizer.congestion.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled jobs of the application.
 *
 * <p>It lives in the congestion module (owner of the first scheduled job) so the annotation
 * is not added to the shared application class; any other module can schedule its own
 * methods once this configuration is on the classpath.
 */
@Configuration
@EnableScheduling
public class CongestionSchedulingConfig {
}
