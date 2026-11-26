package cr.logisticaportuaria.fleet.application.dto;

import cr.logisticaportuaria.fleet.application.FleetMetrics;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * KPIs of the fleet ({@code GET /fleet/kpis}).
 *
 * @param yardId               yard
 * @param from                 inclusive start of the window
 * @param to                   exclusive end of the window
 * @param vehicles             active vehicles of the yard
 * @param utilizationPct       percentage of productive time
 * @param idlePct              percentage of free time
 * @param maintenancePct       percentage of breakdown time
 * @param distanceKm           distance travelled in the window
 * @param averageCycleMinutes  average duration of the completed work orders
 * @param completedWorkOrders  work orders completed in the window
 * @param alertsByType         alerts emitted in the window, by type
 * @param counters             ingestion counters and latency of the process
 */
public record FleetKpisResponse(
        UUID yardId,
        Instant from,
        Instant to,
        int vehicles,
        double utilizationPct,
        double idlePct,
        double maintenancePct,
        double distanceKm,
        Double averageCycleMinutes,
        long completedWorkOrders,
        Map<String, Long> alertsByType,
        FleetMetrics.Snapshot counters) {
}
