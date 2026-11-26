package cr.logisticaportuaria.optimizer.domain;

import cr.logisticaportuaria.yard.domain.ContainerType;
import cr.logisticaportuaria.yard.domain.SlotType;
import java.util.Optional;

/**
 * Physical rules of the yard, shared by the heuristic, the CP-SAT model, the baselines and
 * the property tests. A placement that violates any of them is never proposed.
 *
 * <ul>
 *   <li>size: a 20 ft container only fits a 20 ft slot and a 40 ft container a 40 ft slot
 *       (a 40 ft slot already spans two bays in the schema);</li>
 *   <li>tier: never above the maximum height of the block;</li>
 *   <li>support: a container above the first tier needs an occupied slot below;</li>
 *   <li>weight: a heavier container is never stacked on a lighter one;</li>
 *   <li>reefer: requires a slot with a plug;</li>
 *   <li>hazmat: only in segregated hazmat slots, and hazmat slots accept nothing else.</li>
 * </ul>
 */
public final class PlacementRules {

    private PlacementRules() {
    }

    /**
     * @param snapshot current state of the yard
     * @param slot     candidate slot
     * @param unit     container to place
     * @return whether the placement is physically valid
     */
    public static boolean canPlace(YardSnapshot snapshot, SlotNode slot, ContainerUnit unit) {
        if (slot.sizeFt() != unit.sizeFt()) {
            return false;
        }
        if (slot.tier() > slot.maxTier()) {
            return false;
        }
        if (!snapshot.isFree(slot)) {
            return false;
        }
        if (!acceptsType(slot, unit)) {
            return false;
        }
        if (slot.tier() == 1) {
            return true;
        }
        SlotNode below = snapshot.below(slot).orElse(null);
        if (below == null || snapshot.isFree(below)) {
            return false;
        }
        if (below.sizeFt() != slot.sizeFt()) {
            return false;
        }
        Optional<ContainerUnit> support = snapshot.containerAt(below);
        if (support.isEmpty()) {
            return false;
        }
        ContainerUnit belowUnit = support.get();
        if (belowUnit.weightKg() != null && unit.weightKg() != null
                && unit.weightKg() > belowUnit.weightKg()) {
            return false;
        }
        // The cargo types are compatible because the whole column shares the slot type.
        return true;
    }

    /**
     * @param slot candidate slot
     * @param unit container to place
     * @return whether the slot type and the power supply match the cargo
     */
    public static boolean acceptsType(SlotNode slot, ContainerUnit unit) {
        if (slot.slotType() == SlotType.hazmat) {
            return unit.type() == ContainerType.hazmat && (!unit.needsPower() || slot.hasPower());
        }
        if (unit.type() == ContainerType.hazmat) {
            return false;
        }
        if (unit.needsPower()) {
            return slot.hasPower();
        }
        return slot.slotType() == SlotType.dry;
    }

    /**
     * @param snapshot current state of the yard
     * @param unit     container to place
     * @return the number of slots where the container fits, without scoring them
     */
    public static int countFeasibleSlots(YardSnapshot snapshot, ContainerUnit unit) {
        int count = 0;
        for (SlotNode slot : snapshot.slots()) {
            if (canPlace(snapshot, slot, unit)) {
                count++;
            }
        }
        return count;
    }
}
