package cr.logisticaportuaria.optimizer.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fast ETD-aware heuristic (strategy {@code HEURISTIC}).
 *
 * <p>For one container it scores every feasible slot with {@link SlotScorer} and returns the
 * best {@code topN}. For a batch it places the containers one by one, from the latest ETD to
 * the earliest, so the containers that leave first end up on top of the piles. With
 * {@code n} slots the evaluation of one container is O(n), which is what keeps the gate
 * decision under 200 ms with 5 000 slots.
 */
public final class HeuristicPlanner {

    private final SlotScorer scorer;
    private final int topN;

    /**
     * @param scorer cost function
     * @param topN   number of recommendations returned by {@link #recommend}
     */
    public HeuristicPlanner(SlotScorer scorer, int topN) {
        this.scorer = scorer;
        this.topN = Math.max(1, topN);
    }

    /**
     * Top-N slots for one incoming container.
     *
     * @param snapshot current state of the yard
     * @param unit     incoming container
     * @return the recommendations, best first (empty when there is no feasible slot)
     */
    public List<Recommendation> recommend(YardSnapshot snapshot, ContainerUnit unit) {
        List<SlotScorer.Assessment> assessments = new ArrayList<>();
        for (SlotNode slot : snapshot.slots()) {
            if (!PlacementRules.canPlace(snapshot, slot, unit)) {
                continue;
            }
            assessments.add(scorer.assess(snapshot, slot, unit));
        }
        assessments.sort(Comparator
                .comparingDouble(SlotScorer.Assessment::score)
                .thenComparing(SlotScorer.Assessment::slot, Comparator
                        .comparing(SlotNode::blockCode)
                        .thenComparingInt(SlotNode::bay)
                        .thenComparingInt(SlotNode::row)
                        .thenComparingInt(SlotNode::tier)));
        List<Recommendation> recommendations = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, assessments.size()); i++) {
            SlotScorer.Assessment assessment = assessments.get(i);
            recommendations.add(new Recommendation(i + 1, assessment.slot(), assessment.score(),
                    assessment.explanation()));
        }
        return recommendations;
    }

    /**
     * Batch plan: places every container on the best slot available at that moment.
     *
     * @param snapshot current state of the yard (it is mutated)
     * @param incoming containers to place
     * @param startNanos {@link System#nanoTime()} when the request started
     * @return the plan
     */
    public PlacementPlan plan(YardSnapshot snapshot, List<ContainerUnit> incoming, long startNanos) {
        List<ContainerUnit> ordered = new ArrayList<>(incoming);
        ordered.sort(Comparator
                .comparingLong(ContainerUnit::etdMillisOrFarFuture)
                .reversed()
                .thenComparing(ContainerUnit::code));
        List<Move> moves = new ArrayList<>();
        List<String> unplaced = new ArrayList<>();
        int order = 1;
        for (ContainerUnit unit : ordered) {
            SlotScorer.Assessment best = null;
            for (SlotNode slot : snapshot.slots()) {
                if (!PlacementRules.canPlace(snapshot, slot, unit)) {
                    continue;
                }
                SlotScorer.Assessment assessment = scorer.assess(snapshot, slot, unit);
                if (best == null || assessment.score() < best.score()) {
                    best = assessment;
                }
            }
            if (best == null) {
                unplaced.add(unit.code());
                continue;
            }
            snapshot.place(best.slot(), unit);
            moves.add(Move.place(order++, unit.code(), best.slot()));
        }
        long computationMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
        return new PlacementPlan(moves, unplaced, YardMetrics.of(snapshot), "HEURISTIC", computationMs);
    }

    /**
     * @param reference instant treated as "now" for the urgency of the ETDs
     * @param weights   weights of the objective
     * @param yards     maximum distance of the yard, used to normalize the distance term
     * @return the scorer used by this planner
     */
    public static SlotScorer scorer(StackingWeights weights, YardSnapshot snapshot, Instant reference) {
        double maxDistance = snapshot.slots().stream()
                .mapToDouble(SlotNode::distanceToGateMeters)
                .max()
                .orElse(1.0);
        return new SlotScorer(weights, maxDistance, reference);
    }
}
