package cr.logisticaportuaria.optimizer.application.dto;

import cr.logisticaportuaria.optimizer.domain.YardMetrics;

/**
 * Metrics of an {@code OptimizerPlan}.
 *
 * @param expectedRestows       expected unproductive moves if the current ETDs hold
 * @param averageDistanceMeters average distance to the gate of the stacked containers
 * @param occupancy             occupied slots / total slots
 * @param totalSlots            slots of the yard
 * @param occupiedSlots         slots with a container
 */
public record PlanMetricsDto(
        double expectedRestows,
        double averageDistanceMeters,
        double occupancy,
        int totalSlots,
        int occupiedSlots) {

    /**
     * @param metrics metrics to map
     * @return the DTO
     */
    public static PlanMetricsDto from(YardMetrics metrics) {
        return new PlanMetricsDto(metrics.expectedRestows(), metrics.averageDistanceMeters(), metrics.occupancy(),
                metrics.totalSlots(), metrics.occupiedSlots());
    }
}
