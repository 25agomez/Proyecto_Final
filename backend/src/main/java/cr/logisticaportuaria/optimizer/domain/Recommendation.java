package cr.logisticaportuaria.optimizer.domain;

/**
 * A slot recommended for an incoming container.
 *
 * @param rank        position in the Top-N (1 is the best)
 * @param slot        recommended slot
 * @param score       cost of the placement (lower is better)
 * @param explanation explanation in Spanish for the operator
 */
public record Recommendation(int rank, SlotNode slot, double score, String explanation) {
}
