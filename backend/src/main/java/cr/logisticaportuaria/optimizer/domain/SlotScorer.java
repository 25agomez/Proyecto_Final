package cr.logisticaportuaria.optimizer.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Cost function of the stacking problem.
 *
 * <p>Every term is precomputed for a (container, slot) pair, which keeps the heuristic
 * linear in the number of slots and lets CP-SAT reuse the same costs as a linear
 * objective (see {@code docs/architecture/optimizer-model.md}).
 */
public final class SlotScorer {

    private static final double MAX_RESTOW_PENALTY = 5.0;
    private static final double MAX_BLOCKING_DAYS = 7.0;

    private final StackingWeights weights;
    private final double maxDistanceMeters;
    private final Instant reference;

    /**
     * @param weights            weights of the objective
     * @param maxDistanceMeters  normalizer of the distance term
     * @param reference          instant used to compute the urgency of the ETD
     */
    public SlotScorer(StackingWeights weights, double maxDistanceMeters, Instant reference) {
        this.weights = weights;
        this.maxDistanceMeters = Math.max(1.0, maxDistanceMeters);
        this.reference = reference == null ? Instant.now() : reference;
    }

    /**
     * @return the weights in use
     */
    public StackingWeights weights() {
        return weights;
    }

    /**
     * Scores a placement.
     *
     * @param snapshot current state of the yard
     * @param slot     candidate slot
     * @param unit     container to place
     * @return the assessment, with the explanation in Spanish
     */
    public Assessment assess(YardSnapshot snapshot, SlotNode slot, ContainerUnit unit) {
        double restowCost = restowCost(snapshot, slot, unit);
        double distanceCost = distanceCost(slot, unit);
        double dispersionCost = dispersionCost(snapshot, slot, unit);
        double heightCost = weights.height() * Math.max(0, slot.tier() - 1);
        double weightCost = weightCost(snapshot, slot, unit);
        double score = restowCost + distanceCost + dispersionCost + heightCost + weightCost;
        return new Assessment(slot, round(score), round(restowCost), round(distanceCost), round(dispersionCost),
                round(heightCost), round(weightCost), explain(snapshot, slot, unit, restowCost, dispersionCost));
    }

    /**
     * Penalizes burying a container that leaves earlier, or placing the new one over a
     * pile that leaves later (in that case the pile blocks the new container).
     */
    private double restowCost(YardSnapshot snapshot, SlotNode slot, ContainerUnit unit) {
        long unitEtd = unit.etdMillisOrFarFuture();
        double penalty = 0.0;

        // Containers above the candidate slot (only possible when there are gaps in the pile).
        for (ContainerUnit above : snapshot.containersAbove(slot)) {
            if (above.etdMillisOrFarFuture() > unitEtd) {
                penalty += 1.0 + blockingDays(unit.etd(), above.etd());
            }
        }

        // The container below: if it leaves before the new one, the new one blocks it.
        Optional<SlotNode> below = snapshot.below(slot);
        if (below.isPresent()) {
            Optional<ContainerUnit> support = snapshot.containerAt(below.get());
            if (support.isPresent()) {
                ContainerUnit belowUnit = support.get();
                if (belowUnit.etd() != null && belowUnit.etdMillisOrFarFuture() < unitEtd) {
                    penalty += 1.0 + blockingDays(belowUnit.etd(), unit.etd());
                }
            }
        }
        return Math.min(MAX_RESTOW_PENALTY, penalty) * weights.restow();
    }

    private double blockingDays(Instant earlier, Instant later) {
        if (earlier == null || later == null) {
            return 0.0;
        }
        double days = Duration.between(earlier, later).toHours() / 24.0;
        return Math.min(MAX_BLOCKING_DAYS, Math.max(0.0, days));
    }

    /**
     * Distance to the gate, weighted by the urgency of the departure: a container that
     * leaves soon pays more for being far away.
     */
    private double distanceCost(SlotNode slot, ContainerUnit unit) {
        double normalized = Math.min(1.0, slot.distanceToGateMeters() / maxDistanceMeters);
        return weights.distance() * normalized * urgency(unit.etd());
    }

    private double urgency(Instant etd) {
        if (etd == null) {
            return 0.5;
        }
        double hours = Duration.between(reference, etd).toHours();
        if (hours <= 0) {
            return 1.0;
        }
        double days = Math.min(MAX_BLOCKING_DAYS, hours / 24.0);
        return Math.max(0.1, 1.0 - 0.9 * days / MAX_BLOCKING_DAYS);
    }

    /**
     * Bonus for stacking next to another container of the same client (lowest tier of the
     * neighbouring bays of the same row).
     */
    private double dispersionCost(YardSnapshot snapshot, SlotNode slot, ContainerUnit unit) {
        if (unit.clientCode() == null) {
            return 0.0;
        }
        for (int bay : new int[] {slot.bay() - 1, slot.bay() + 1}) {
            if (bay < 1) {
                continue;
            }
            boolean sameClient = snapshot.lowestContainerAt(slot.blockCode(), bay, slot.row())
                    .map(neighbour -> unit.clientCode().equals(neighbour.clientCode()))
                    .orElse(false);
            if (sameClient) {
                return -weights.dispersion();
            }
        }
        return 0.0;
    }

    private double weightCost(YardSnapshot snapshot, SlotNode slot, ContainerUnit unit) {
        if (slot.tier() == 1 || unit.weightKg() == null) {
            return 0.0;
        }
        Optional<SlotNode> below = snapshot.below(slot);
        if (below.isEmpty()) {
            return 0.0;
        }
        Optional<ContainerUnit> support = snapshot.containerAt(below.get());
        if (support.isEmpty() || support.get().weightKg() == null) {
            return 0.0;
        }
        double difference = unit.weightKg() - support.get().weightKg();
        return difference > 0 ? weights.weightOrder() * difference / 1000.0 : 0.0;
    }

    private String explain(YardSnapshot snapshot, SlotNode slot, ContainerUnit unit, double restowCost,
            double dispersionCost) {
        StringBuilder text = new StringBuilder();
        text.append("Bloque ").append(slot.blockCode())
                .append(", bahía ").append(slot.bay())
                .append(", fila ").append(slot.row())
                .append(", nivel ").append(slot.tier()).append(": ");
        text.append(String.format(Locale.ROOT, "a %.0f m de la puerta", slot.distanceToGateMeters()));
        if (slot.tier() == 1) {
            text.append("; base libre (sin apoyo)");
        } else {
            text.append("; apoyado sobre un contenedor de la misma pila");
        }
        long unitEtd = unit.etdMillisOrFarFuture();
        int blockedAbove = 0;
        for (ContainerUnit above : snapshot.containersAbove(slot)) {
            if (above.etdMillisOrFarFuture() > unitEtd) {
                blockedAbove++;
            }
        }
        if (blockedAbove > 0) {
            text.append("; quedaría tapado por ").append(blockedAbove).append(" contenedor(es) que salen después");
        }
        if (restowCost > 0 && blockedAbove == 0) {
            text.append("; bloquearía un contenedor que sale antes");
        }
        if (dispersionCost < 0) {
            text.append("; junto a otro contenedor del cliente ").append(unit.clientCode());
        }
        if (unit.needsPower()) {
            text.append("; con toma eléctrica");
        }
        text.append(".");
        return text.toString();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /**
     * Result of scoring a slot.
     *
     * @param slot           scored slot
     * @param score          total cost (lower is better)
     * @param restowCost     restow component
     * @param distanceCost   distance component
     * @param dispersionCost client dispersion component (negative is a bonus)
     * @param heightCost     height component
     * @param weightCost     weight order component
     * @param explanation    explanation in Spanish for the operator
     */
    public record Assessment(
            SlotNode slot,
            double score,
            double restowCost,
            double distanceCost,
            double dispersionCost,
            double heightCost,
            double weightCost,
            String explanation) {
    }
}
