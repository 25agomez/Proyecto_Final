package cr.logisticaportuaria.optimizer.application.dto;

import java.util.List;

/**
 * Response of {@code POST /api/v1/optimizer/recommendations} ({@code OptimizerResponse}).
 *
 * @param strategy        strategy used ({@code HEURISTIC})
 * @param computationMs   time spent computing the recommendations
 * @param recommendations Top-N slots, best first
 */
public record OptimizerResponse(String strategy, long computationMs, List<RecommendationDto> recommendations) {

    /**
     * Canonical constructor: the list is copied so the response is immutable.
     */
    public OptimizerResponse {
        recommendations = List.copyOf(recommendations);
    }
}
