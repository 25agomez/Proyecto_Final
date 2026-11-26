package cr.logisticaportuaria.optimizer.application;

import cr.logisticaportuaria.optimizer.domain.StackingWeights;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the stacking optimizer ({@code app.optimizer.*}).
 */
@Validated
@ConfigurationProperties(prefix = "app.optimizer")
public class OptimizerProperties {

    /**
     * Yard used by the endpoints; when it is {@code null} the optimizer loads every yard
     * (the demo runs a single yard).
     */
    private UUID yardId;

    /** Number of recommendations returned by the Top-N. */
    @Min(1)
    @Max(20)
    private int topRecommendations = 3;

    /** Candidate slots kept per container before building the CP-SAT model. */
    @Min(2)
    @Max(500)
    private int maxCandidates = 32;

    /** Default time limit of the asynchronous plans, in seconds. */
    @Min(1)
    @Max(600)
    private int defaultTimeLimitSeconds = 30;

    /** Weights of the objective function. */
    @Valid
    private Weights weights = new Weights();

    /**
     * @return the yard of the optimizer
     */
    public UUID getYardId() {
        return yardId;
    }

    public void setYardId(UUID yardId) {
        this.yardId = yardId;
    }

    public int getTopRecommendations() {
        return topRecommendations;
    }

    public void setTopRecommendations(int topRecommendations) {
        this.topRecommendations = topRecommendations;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public int getDefaultTimeLimitSeconds() {
        return defaultTimeLimitSeconds;
    }

    public void setDefaultTimeLimitSeconds(int defaultTimeLimitSeconds) {
        this.defaultTimeLimitSeconds = defaultTimeLimitSeconds;
    }

    public Weights getWeights() {
        return weights;
    }

    public void setWeights(Weights weights) {
        this.weights = weights;
    }

    /**
     * @return the weights as an immutable record used by the domain
     */
    public StackingWeights toStackingWeights() {
        return new StackingWeights(weights.getRestow(), weights.getDistance(), weights.getDispersion(),
                weights.getHeight(), weights.getWeightOrder());
    }

    /**
     * Weights of the objective ({@code app.optimizer.weights.*}); lower is better.
     */
    public static class Weights {

        /** Cost of blocking a container that departs earlier. */
        @DecimalMin("0.0")
        private double restow = 4.0;

        /** Cost of the distance to the gate. */
        @DecimalMin("0.0")
        private double distance = 2.0;

        /** Bonus for stacking next to another container of the same client. */
        @DecimalMin("0.0")
        private double dispersion = 0.5;

        /** Cost of using a higher tier. */
        @DecimalMin("0.0")
        private double height = 0.3;

        /** Cost of stacking on a lighter container. */
        @DecimalMin("0.0")
        private double weightOrder = 1.0;

        public double getRestow() {
            return restow;
        }

        public void setRestow(double restow) {
            this.restow = restow;
        }

        public double getDistance() {
            return distance;
        }

        public void setDistance(double distance) {
            this.distance = distance;
        }

        public double getDispersion() {
            return dispersion;
        }

        public void setDispersion(double dispersion) {
            this.dispersion = dispersion;
        }

        public double getHeight() {
            return height;
        }

        public void setHeight(double height) {
            this.height = height;
        }

        public double getWeightOrder() {
            return weightOrder;
        }

        public void setWeightOrder(double weightOrder) {
            this.weightOrder = weightOrder;
        }
    }
}
