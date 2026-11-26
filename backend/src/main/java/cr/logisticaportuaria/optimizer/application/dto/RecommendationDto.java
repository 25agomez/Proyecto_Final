package cr.logisticaportuaria.optimizer.application.dto;

import cr.logisticaportuaria.optimizer.domain.ContainerUnit;
import cr.logisticaportuaria.optimizer.domain.Recommendation;

/**
 * Recommendation of the {@code OptimizerResponse} schema.
 *
 * @param rank        position in the Top-N (1 is the best)
 * @param slot        recommended slot
 * @param score       cost of the placement (lower is better)
 * @param explanation explanation in Spanish for the operator
 */
public record RecommendationDto(int rank, SlotDto slot, double score, String explanation) {

    /**
     * @param recommendation recommendation to map
     * @param containerAt    container currently stacked in the slot (null when it is free)
     * @return the DTO
     */
    public static RecommendationDto from(Recommendation recommendation, ContainerUnit containerAt) {
        return new RecommendationDto(recommendation.rank(), SlotDto.from(recommendation.slot(), containerAt),
                recommendation.score(), recommendation.explanation());
    }
}
