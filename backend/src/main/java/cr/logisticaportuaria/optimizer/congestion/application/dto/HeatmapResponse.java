package cr.logisticaportuaria.optimizer.congestion.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response of {@code GET /api/v1/congestion/heatmap}.
 *
 * @param gateId gate of the heatmap, or {@code null} when it aggregates every gate
 * @param from   inclusive start of the window
 * @param to     exclusive end of the window
 * @param cells  non-empty cells
 * @param matrix 7 x 24 mean waits in minutes (row 0 = Monday)
 */
public record HeatmapResponse(UUID gateId, Instant from, Instant to, List<Cell> cells, double[][] matrix) {

    /**
     * One cell of the heatmap.
     *
     * @param dayOfWeek      ISO day of the week (1 = Monday)
     * @param hour           hour of the day in Costa Rica
     * @param averageWaitMin mean wait in minutes
     * @param samples        number of visits
     */
    public record Cell(int dayOfWeek, int hour, double averageWaitMin, int samples) {
    }
}
