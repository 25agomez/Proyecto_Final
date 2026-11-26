package cr.logisticaportuaria.optimizer.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Naive baselines used by the benchmark to measure the value of the optimizer.
 *
 * <p>They respect the physical rules (size, support, weight, reefer, hazmat), so the
 * comparison is about <em>stacking quality</em> and not about producing invalid yards.
 */
public final class BaselinePlanner {

    /**
     * Strategy of the baseline.
     */
    public enum Strategy {
        /** First fit in storage order: the first free slot that fits. */
        FIFO,
        /** Uniform random choice among the feasible slots. */
        RANDOM
    }

    private BaselinePlanner() {
    }

    /**
     * @param snapshot  current state of the yard (it is mutated)
     * @param incoming  containers in arrival order
     * @param strategy  baseline to apply
     * @param seed      seed of the RANDOM baseline
     * @param startNanos {@link System#nanoTime()} when the run started
     * @return the plan
     */
    public static PlacementPlan plan(YardSnapshot snapshot, List<ContainerUnit> incoming, Strategy strategy,
            long seed, long startNanos) {
        Random random = new Random(seed);
        List<Move> moves = new ArrayList<>();
        List<String> unplaced = new ArrayList<>();
        List<SlotNode> slots = snapshot.slots();
        int order = 1;
        for (ContainerUnit unit : incoming) {
            SlotNode chosen = null;
            if (strategy == Strategy.FIFO) {
                for (SlotNode slot : slots) {
                    if (PlacementRules.canPlace(snapshot, slot, unit)) {
                        chosen = slot;
                        break;
                    }
                }
            } else {
                List<SlotNode> feasible = new ArrayList<>();
                for (SlotNode slot : slots) {
                    if (PlacementRules.canPlace(snapshot, slot, unit)) {
                        feasible.add(slot);
                    }
                }
                if (!feasible.isEmpty()) {
                    chosen = feasible.get(random.nextInt(feasible.size()));
                }
            }
            if (chosen == null) {
                unplaced.add(unit.code());
                continue;
            }
            snapshot.place(chosen, unit);
            moves.add(Move.place(order++, unit.code(), chosen));
        }
        long computationMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
        return new PlacementPlan(moves, unplaced, YardMetrics.of(snapshot), strategy.name(), computationMs);
    }
}
