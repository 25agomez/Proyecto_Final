package cr.logisticaportuaria.optimizer.domain;

import cr.logisticaportuaria.yard.domain.ContainerType;
import cr.logisticaportuaria.yard.domain.SlotType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Builders shared by the optimizer tests.
 */
final class OptimizerFixtures {

    static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    private OptimizerFixtures() {
    }

    static SlotNode slot(String block, int bay, int row, int tier, int sizeFt, SlotType type, boolean power) {
        UUID id = UUID.nameUUIDFromBytes(("%s-%d-%d-%d").formatted(block, bay, row, tier)
                .getBytes(StandardCharsets.UTF_8));
        return new SlotNode(id, block, bay, row, tier, sizeFt, type, power, 4,
                DistanceModel.distanceOf(block, bay, row, tier));
    }

    static SlotNode drySlot(String block, int bay, int row, int tier, int sizeFt) {
        return slot(block, bay, row, tier, sizeFt, SlotType.dry, false);
    }

    static ContainerUnit dry(String code, int sizeFt, double weightKg, Instant etd, String client) {
        return ContainerUnit.incoming(code, sizeFt, ContainerType.dry, weightKg, etd, 6.0, client, false, null);
    }

    static ContainerUnit reefer(String code, int sizeFt, Instant etd) {
        return ContainerUnit.incoming(code, sizeFt, ContainerType.reefer, 18_000.0, etd, 6.0, "ACME", true, null);
    }

    static ContainerUnit hazmat(String code, int sizeFt, Instant etd) {
        return ContainerUnit.incoming(code, sizeFt, ContainerType.hazmat, 20_000.0, etd, 6.0, "ACME", false, "3");
    }

    static SlotScorer scorer(YardSnapshot snapshot) {
        return HeuristicPlanner.scorer(StackingWeights.DEFAULT, snapshot, NOW);
    }
}
