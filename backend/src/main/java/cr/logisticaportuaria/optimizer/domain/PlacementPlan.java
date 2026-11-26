package cr.logisticaportuaria.optimizer.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a batch stacking plan.
 *
 * @param moves         steps to execute, in order
 * @param unplaced      containers that could not be placed (no feasible slot)
 * @param metrics       indicators of the yard after the plan
 * @param solverStatus  status of the solver ({@code OPTIMAL}, {@code FEASIBLE}, {@code INFEASIBLE}...)
 * @param computationMs time spent computing the plan
 */
public record PlacementPlan(
        List<Move> moves,
        List<String> unplaced,
        YardMetrics metrics,
        String solverStatus,
        long computationMs) {

    /**
     * Canonical constructor: copies the lists so the plan is immutable.
     */
    public PlacementPlan {
        moves = List.copyOf(moves);
        unplaced = List.copyOf(unplaced);
    }

    /**
     * @return whether every requested container was placed
     */
    public boolean complete() {
        return unplaced.isEmpty();
    }

    /**
     * @return the number of containers placed
     */
    public int placed() {
        return moves.size();
    }

    /**
     * @param plan   plan to copy
     * @param status solver status to report
     * @return the same plan with another solver status
     */
    public static PlacementPlan withStatus(PlacementPlan plan, String status) {
        return new PlacementPlan(plan.moves(), plan.unplaced(), plan.metrics(), status, plan.computationMs());
    }

    /**
     * @return an empty plan for an empty request
     */
    public static PlacementPlan empty(YardSnapshot snapshot, long computationMs) {
        return new PlacementPlan(new ArrayList<>(), new ArrayList<>(), YardMetrics.of(snapshot), "OPTIMAL",
                computationMs);
    }
}
