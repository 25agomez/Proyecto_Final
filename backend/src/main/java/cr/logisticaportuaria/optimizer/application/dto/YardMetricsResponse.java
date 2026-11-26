package cr.logisticaportuaria.optimizer.application.dto;

import cr.logisticaportuaria.optimizer.domain.YardMetrics;
import java.time.Instant;
import java.util.UUID;

/**
 * Response of {@code GET /api/v1/optimizer/metrics} (extension of the contract, used by the
 * dashboard of the yard).
 *
 * @param yardId                 yard measured (null when the optimizer loads every yard)
 * @param totalSlots             slots of the yard
 * @param occupiedSlots          slots with a container
 * @param occupancy              occupied / total (0..1)
 * @param expectedRestows        expected unproductive moves if the current ETDs hold
 * @param averageDistanceMeters  average distance to the gate of the stacked containers
 * @param generatedAt            instant of the measurement
 */
public record YardMetricsResponse(
        UUID yardId,
        int totalSlots,
        int occupiedSlots,
        double occupancy,
        double expectedRestows,
        double averageDistanceMeters,
        Instant generatedAt) {

    /**
     * @param yardId  yard measured
     * @param metrics metrics to map
     * @return the DTO
     */
    public static YardMetricsResponse from(UUID yardId, YardMetrics metrics) {
        return new YardMetricsResponse(yardId, metrics.totalSlots(), metrics.occupiedSlots(), metrics.occupancy(),
                metrics.expectedRestows(), metrics.averageDistanceMeters(), Instant.now());
    }
}
