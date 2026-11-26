package cr.logisticaportuaria.optimizer.domain;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stacking plan with CP-SAT ({@code OR-Tools}, strategy {@code CP_SAT}).
 *
 * <p>The model is a minimum-cost assignment with side constraints; the complete
 * mathematical formulation (variables, constraints, objective, linearizations and the
 * candidate pruning that keeps the model tractable) is documented in
 * {@code docs/architecture/optimizer-model.md}. In short:
 *
 * <ul>
 *   <li>{@code x[i][k] = 1} when incoming container {@code i} goes to its {@code k}-th
 *       candidate slot;</li>
 *   <li>one slot per container, one container per slot;</li>
 *   <li>support: an upper tier can only be used if the slot below is already occupied or
 *       is filled by another container of this same plan;</li>
 *   <li>the cost of each pair comes from {@link SlotScorer}, so both strategies share the
 *       same objective and their results are comparable.</li>
 * </ul>
 *
 * <p>The candidate list of each container is pruned to {@code maxCandidates} feasible slots
 * before building the model; without that pruning the model would have millions of booleans
 * for the benchmark sizes. Half of the candidates are the cheapest slots (quality) and the
 * other half are spread over the ranking (diversity): with only the cheapest ones every
 * container would propose the same few slots, the union would be smaller than the batch and
 * the model would become infeasible. The pruning is documented as a decomposition, not
 * hidden as an exact solution.
 *
 * <p>Each container also has an explicit {@code skip} variable: the model always has the
 * feasible "leave it unplaced" solution, so the solver always returns a plan even when the
 * candidates of the batch cannot cover every container. The heuristic plan is hinted to the
 * solver (warm start) and the batch is solved in chunks of {@value #MAX_BATCH} containers in
 * ETD order (rolling horizon), because a model of thousands of containers does not even reach
 * the first solution inside the time limit.
 *
 * <p>Those three decompositions (candidate pruning, warm start and chunks) are documented in
 * {@code docs/architecture/optimizer-model.md}; the benchmark in
 * {@code docs/experiments/EXP-004-stacking-benchmark.md} publishes their measured effect.
 */
public final class CpSatPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(CpSatPlanner.class);

    private static final AtomicBoolean NATIVE_LIBRARIES_LOADED = new AtomicBoolean(false);

    /**
     * Cost of leaving a container unplaced. It is much larger than any placement cost
     * (scores are multiplied by 1 000) so the solver first maximises the number of placed
     * containers and then minimises the cost of the stacking.
     */
    private static final long UNPLACED_PENALTY = 1_000_000_000L;

    /**
     * Maximum number of containers of one CP-SAT model. The batch is solved in chunks of this
     * size (rolling horizon) to keep the model tractable.
     */
    private static final int MAX_BATCH = 250;

    private final SlotScorer scorer;
    private final int maxCandidates;

    /**
     * @param scorer        cost function (shared with the heuristic)
     * @param maxCandidates number of candidate slots kept per container
     */
    public CpSatPlanner(SlotScorer scorer, int maxCandidates) {
        this.scorer = scorer;
        this.maxCandidates = Math.max(2, maxCandidates);
    }

    /**
     * Solves the plan.
     *
     * <p>Large batches are decomposed into chunks of at most {@value #MAX_BATCH} containers
     * (rolling horizon): the model of a whole batch of thousands of containers is too big for
     * the solver to find even the first solution inside the time limit. Each chunk is solved
     * against the yard left by the previous one and receives a share of the remaining time.
     *
     * @param snapshot         current state of the yard (it is mutated with the solution)
     * @param incoming         containers to place
     * @param timeLimitSeconds hard time limit of the solver (1..600)
     * @return the plan, with the solver status ({@code OPTIMAL}, {@code FEASIBLE} or
     *         {@code UNKNOWN} when a chunk could not find a solution in time)
     */
    public PlacementPlan plan(YardSnapshot snapshot, List<ContainerUnit> incoming, int timeLimitSeconds) {
        long start = System.nanoTime();
        if (incoming.isEmpty()) {
            return PlacementPlan.empty(snapshot, 0);
        }
        ensureNativeLibraries();
        int limit = Math.max(1, Math.min(600, timeLimitSeconds));
        long deadlineNanos = start + limit * 1_000_000_000L;
        // Same order as the heuristic: the containers that depart later are placed first, so
        // they end up at the bottom of the stacks. Without it the static cost of the model
        // cannot see the interference between containers of the same chunk.
        List<ContainerUnit> ordered = new ArrayList<>(incoming);
        ordered.sort(Comparator.comparingLong(ContainerUnit::etdMillisOrFarFuture)
                .reversed()
                .thenComparing(ContainerUnit::code));
        int chunkCount = (ordered.size() + MAX_BATCH - 1) / MAX_BATCH;
        List<Move> moves = new ArrayList<>();
        List<String> unplaced = new ArrayList<>();
        String status = "FEASIBLE";
        int order = 1;
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int from = chunkIndex * MAX_BATCH;
            int to = Math.min(ordered.size(), from + MAX_BATCH);
            Chunk chunk = solve(snapshot, ordered.subList(from, to),
                    budgetSeconds(deadlineNanos, chunkCount - chunkIndex));
            for (Move move : chunk.moves()) {
                moves.add(new Move(order++, move.action(), move.containerCode(), move.fromSlotId(),
                        move.toSlotId(), move.toSlotCode(), move.restows()));
            }
            unplaced.addAll(chunk.unplaced());
            if (chunkCount == 1) {
                status = chunk.status();
            } else if (!"OPTIMAL".equals(chunk.status()) && !"FEASIBLE".equals(chunk.status())) {
                status = "UNKNOWN";
            }
        }
        long elapsed = Math.max(0, (System.nanoTime() - start) / 1_000_000);
        return new PlacementPlan(moves, unplaced, YardMetrics.of(snapshot), status, elapsed);
    }

    /**
     * @param deadlineNanos end of the whole plan
     * @param chunksLeft    chunks that still have to be solved (including the current one)
     * @return the time of the current chunk: the time left divided by the chunks left, at least
     *         one second
     */
    private int budgetSeconds(long deadlineNanos, int chunksLeft) {
        long remainingMs = Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000);
        long secondsLeft = Math.max(1, (remainingMs + 999) / 1000);
        return (int) Math.max(1, Math.min(secondsLeft, Math.ceil((double) secondsLeft / chunksLeft)));
    }

    /**
     * Builds and solves the model of one chunk.
     *
     * @param snapshot         current state of the yard (it is mutated with the solution)
     * @param incoming         containers of the chunk
     * @param timeLimitSeconds time limit of the solver
     * @return the moves of the chunk, the containers it could not place and its status
     */
    private Chunk solve(YardSnapshot snapshot, List<ContainerUnit> incoming, int timeLimitSeconds) {
        // Warm start: the heuristic plan is hinted to the solver, so CP-SAT starts from a
        // valid plan and improves it as a whole instead of searching from scratch.
        Map<String, UUID> warmStart = warmStart(snapshot, incoming);
        List<List<SlotScorer.Assessment>> candidates = candidates(snapshot, incoming, warmStart);
        List<String> unplaced = new ArrayList<>();
        for (int i = 0; i < incoming.size(); i++) {
            if (candidates.get(i).isEmpty()) {
                unplaced.add(incoming.get(i).code());
            }
        }

        CpModel model = new CpModel();
        List<List<BoolVar>> vars = new ArrayList<>();
        List<List<SlotScorer.Assessment>> usable = new ArrayList<>();
        List<BoolVar> skips = new ArrayList<>();
        Map<UUID, List<Literal>> usage = new LinkedHashMap<>();

        boolean[] hasCandidates = new boolean[incoming.size()];
        for (int i = 0; i < incoming.size(); i++) {
            hasCandidates[i] = !candidates.get(i).isEmpty();
        }

        // Fixpoint: an upper tier is only usable if its support slot can be filled by some
        // container of this chunk (or is already occupied). Requires at most maxTier passes.
        boolean changed = true;
        while (changed) {
            changed = pruneUnsupported(snapshot, candidates, hasCandidates);
        }

        for (int i = 0; i < incoming.size(); i++) {
            if (!hasCandidates[i]) {
                vars.add(List.of());
                usable.add(List.of());
                skips.add(null);
                continue;
            }
            List<BoolVar> row = new ArrayList<>();
            List<SlotScorer.Assessment> rowAssessments = new ArrayList<>();
            UUID hintedSlot = warmStart.get(incoming.get(i).code());
            boolean hinted = false;
            for (int k = 0; k < candidates.get(i).size(); k++) {
                SlotScorer.Assessment assessment = candidates.get(i).get(k);
                if (assessment == null) {
                    continue;
                }
                BoolVar var = model.newBoolVar("x_%d_%d".formatted(i, k));
                row.add(var);
                rowAssessments.add(assessment);
                usage.computeIfAbsent(assessment.slot().id(), id -> new ArrayList<>()).add(var);
                if (assessment.slot().id().equals(hintedSlot)) {
                    model.addHint(var, 1);
                    hinted = true;
                }
            }
            BoolVar skip = model.newBoolVar("skip_%d".formatted(i));
            model.addHint(skip, hinted ? 0 : 1);
            vars.add(row);
            usable.add(rowAssessments);
            skips.add(skip);
        }

        for (int i = 0; i < vars.size(); i++) {
            if (vars.get(i).isEmpty()) {
                continue;
            }
            List<Literal> exactlyOne = new ArrayList<>(vars.get(i));
            exactlyOne.add(skips.get(i));
            model.addExactlyOne(exactlyOne);
        }
        for (List<Literal> slotVars : usage.values()) {
            if (slotVars.size() > 1) {
                model.addAtMostOne(slotVars.toArray(new Literal[0]));
            }
        }

        // Support: if the slot below is free, some container of the chunk must fill it.
        for (int i = 0; i < usable.size(); i++) {
            for (int k = 0; k < usable.get(i).size(); k++) {
                SlotNode slot = usable.get(i).get(k).slot();
                if (slot.tier() == 1) {
                    continue;
                }
                Optional<SlotNode> below = snapshot.below(slot);
                if (below.isEmpty() || !snapshot.isFree(below.get())) {
                    continue;
                }
                List<Literal> belowVars = usage.getOrDefault(below.get().id(), List.of());
                if (belowVars.isEmpty()) {
                    continue;
                }
                LinearExprBuilder support = LinearExpr.newBuilder();
                belowVars.forEach(support::add);
                support.addTerm(vars.get(i).get(k), -1);
                model.addGreaterOrEqual(support.build(), 0);
            }
        }

        LinearExprBuilder objective = LinearExpr.newBuilder();
        for (int i = 0; i < usable.size(); i++) {
            for (int k = 0; k < usable.get(i).size(); k++) {
                long coefficient = Math.round(usable.get(i).get(k).score() * 1000.0);
                objective.addTerm(vars.get(i).get(k), coefficient);
            }
        }
        for (BoolVar skip : skips) {
            if (skip != null) {
                objective.addTerm(skip, UNPLACED_PENALTY);
            }
        }
        model.minimize(objective.build());

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(Math.max(1, timeLimitSeconds));
        solver.getParameters().setNumSearchWorkers(1);
        solver.getParameters().setRandomSeed(42);
        CpSolverStatus status = solver.solve(model);
        LOGGER.debug("CP-SAT terminó con estado {} en {} ms", status, solver.wallTime());

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            List<String> all = incoming.stream().map(ContainerUnit::code).toList();
            return new Chunk(List.of(), all, status.name());
        }

        List<Chosen> chosen = new ArrayList<>();
        for (int i = 0; i < vars.size(); i++) {
            for (int k = 0; k < vars.get(i).size(); k++) {
                if (solver.booleanValue(vars.get(i).get(k))) {
                    SlotScorer.Assessment assessment = usable.get(i).get(k);
                    chosen.add(new Chosen(incoming.get(i), assessment.slot(), assessment.score()));
                }
            }
        }
        for (int i = 0; i < skips.size(); i++) {
            if (skips.get(i) != null && solver.booleanValue(skips.get(i))) {
                unplaced.add(incoming.get(i).code());
            }
        }
        chosen.sort(Comparator
                .comparing((Chosen chosenPlacement) -> chosenPlacement.slot().blockCode())
                .thenComparingInt(chosenPlacement -> chosenPlacement.slot().bay())
                .thenComparingInt(chosenPlacement -> chosenPlacement.slot().row())
                .thenComparingInt(chosenPlacement -> chosenPlacement.slot().tier()));

        List<Move> moves = new ArrayList<>();
        int order = 1;
        for (Chosen placement : chosen) {
            snapshot.place(placement.slot(), placement.unit());
            moves.add(Move.place(order++, placement.unit().code(), placement.slot()));
        }
        return new Chunk(moves, unplaced, status.name());
    }

    /**
     * Runs the heuristic on a copy of the yard to obtain a valid plan to hint to the solver.
     *
     * @param snapshot current state of the yard (it is not modified)
     * @param incoming containers to place
     * @return slot chosen by the heuristic for each container
     */
    private Map<String, UUID> warmStart(YardSnapshot snapshot, List<ContainerUnit> incoming) {
        PlacementPlan warm = new HeuristicPlanner(scorer, 1).plan(snapshot.copy(), incoming, System.nanoTime());
        Map<String, UUID> hint = new LinkedHashMap<>();
        for (Move move : warm.moves()) {
            hint.put(move.containerCode(), move.toSlotId());
        }
        return hint;
    }

    /**
     * @param snapshot  current state of the yard
     * @param incoming  containers to place
     * @param warmStart slot chosen by the heuristic for each container (always kept as a
     *                  candidate so the hint is consistent with the model)
     * @return the pruned candidate list of each container
     */
    private List<List<SlotScorer.Assessment>> candidates(YardSnapshot snapshot, List<ContainerUnit> incoming,
            Map<String, UUID> warmStart) {
        List<List<SlotScorer.Assessment>> candidates = new ArrayList<>(incoming.size());
        for (ContainerUnit unit : incoming) {
            List<SlotScorer.Assessment> feasible = new ArrayList<>();
            for (SlotNode slot : snapshot.slots()) {
                if (PlacementRules.canPlace(snapshot, slot, unit)) {
                    feasible.add(scorer.assess(snapshot, slot, unit));
                }
            }
            feasible.sort(Comparator.comparingDouble(SlotScorer.Assessment::score)
                    .thenComparing(SlotScorer.Assessment::slot, Comparator
                            .comparing(SlotNode::blockCode)
                            .thenComparingInt(SlotNode::bay)
                            .thenComparingInt(SlotNode::row)
                            .thenComparingInt(SlotNode::tier)));
            List<SlotScorer.Assessment> selected = diverse(feasible);
            keepHintedSlot(selected, feasible, warmStart.get(unit.code()));
            candidates.add(selected);
        }
        return candidates;
    }

    /**
     * Replaces the last (spread) candidate with the slot chosen by the heuristic, so the warm
     * start is always a hint the model can accept.
     *
     * @param selected candidates kept for the container
     * @param feasible every feasible slot of the container, best first
     * @param hinted   slot chosen by the heuristic (may be null)
     */
    private void keepHintedSlot(List<SlotScorer.Assessment> selected, List<SlotScorer.Assessment> feasible,
            UUID hinted) {
        if (hinted == null || selected.isEmpty()
                || selected.stream().anyMatch(assessment -> assessment.slot().id().equals(hinted))) {
            return;
        }
        feasible.stream()
                .filter(assessment -> assessment.slot().id().equals(hinted))
                .findFirst()
                .ifPresent(assessment -> selected.set(selected.size() - 1, assessment));
    }

    /**
     * Keeps the cheapest half of the candidates and spreads the other half over the rest of
     * the ranking.
     *
     * <p>With only the cheapest slots every container proposes the same few slots: the union
     * of the candidates would be smaller than the batch and the model would be infeasible
     * (the solver would spend the whole time limit without finding a solution). The spread
     * half gives the model the alternatives it needs to reach every container; the objective
     * still prefers the cheap ones.
     *
     * @param sorted feasible slots, best first
     * @return at most {@code maxCandidates} slots
     */
    private List<SlotScorer.Assessment> diverse(List<SlotScorer.Assessment> sorted) {
        if (sorted.size() <= maxCandidates) {
            return sorted;
        }
        int cheapest = Math.max(1, maxCandidates / 2);
        Set<SlotScorer.Assessment> picked = new LinkedHashSet<>(sorted.subList(0, cheapest));
        int remaining = maxCandidates - cheapest;
        int available = sorted.size() - cheapest;
        for (int j = 0; j < remaining && available > 0; j++) {
            int index = cheapest + (int) ((long) j * available / remaining);
            picked.add(sorted.get(Math.min(sorted.size() - 1, index)));
        }
        int cursor = cheapest;
        while (picked.size() < maxCandidates && cursor < sorted.size()) {
            picked.add(sorted.get(cursor++));
        }
        return new ArrayList<>(picked);
    }

    /**
     * Drops the candidates that need a free support slot that no other candidate can fill.
     *
     * @return whether any candidate was dropped
     */
    private boolean pruneUnsupported(YardSnapshot snapshot,
            List<List<SlotScorer.Assessment>> candidates, boolean[] hasCandidates) {
        Set<UUID> fillable = new LinkedHashSet<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (!hasCandidates[i]) {
                continue;
            }
            for (SlotScorer.Assessment assessment : candidates.get(i)) {
                fillable.add(assessment.slot().id());
            }
        }
        boolean changed = false;
        for (int i = 0; i < candidates.size(); i++) {
            if (!hasCandidates[i]) {
                continue;
            }
            List<SlotScorer.Assessment> kept = new ArrayList<>();
            for (SlotScorer.Assessment assessment : candidates.get(i)) {
                SlotNode slot = assessment.slot();
                boolean supported = true;
                if (slot.tier() > 1) {
                    Optional<SlotNode> below = snapshot.below(slot);
                    if (below.isPresent() && snapshot.isFree(below.get())) {
                        supported = fillable.contains(below.get().id());
                    }
                }
                if (supported) {
                    kept.add(assessment);
                }
            }
            if (kept.size() != candidates.get(i).size()) {
                candidates.set(i, kept);
                hasCandidates[i] = !kept.isEmpty();
                changed = true;
            }
        }
        return changed;
    }

    private void ensureNativeLibraries() {
        if (NATIVE_LIBRARIES_LOADED.compareAndSet(false, true)) {
            Loader.loadNativeLibraries();
        }
    }

    private record Chosen(ContainerUnit unit, SlotNode slot, double score) {
    }

    private record Chunk(List<Move> moves, List<String> unplaced, String status) {
    }
}
