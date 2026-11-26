package cr.logisticaportuaria.optimizer.domain;

/**
 * Weights of the objective function of the stacking engine ({@code app.optimizer.weights.*}).
 *
 * <p>All the terms are additive and expressed in the same unit ("cost points"), so the
 * planner can compare a restow against a meter walked by the crane. Lower is better.
 *
 * @param restow      cost of blocking a container that departs earlier
 * @param distance    cost of walking/lifting distance to the gate
 * @param dispersion  bonus for stacking next to another container of the same client
 * @param height      cost of using a higher tier
 * @param weightOrder cost of stacking on a lighter container (only when the weights are known)
 */
public record StackingWeights(
        double restow,
        double distance,
        double dispersion,
        double height,
        double weightOrder) {

    /** Defaults used when {@code app.optimizer.weights.*} is not configured. */
    public static final StackingWeights DEFAULT = new StackingWeights(4.0, 2.0, 0.5, 0.3, 1.0);

    /**
     * Validates the weights.
     */
    public StackingWeights {
        if (restow < 0 || distance < 0 || dispersion < 0 || height < 0 || weightOrder < 0) {
            throw new IllegalArgumentException("Los pesos del optimizador no pueden ser negativos.");
        }
    }
}
