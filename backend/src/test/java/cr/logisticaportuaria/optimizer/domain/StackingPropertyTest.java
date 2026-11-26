package cr.logisticaportuaria.optimizer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Property test of the heuristic: 200 generated yards, every returned move must be valid
 * against the state of the yard at that moment and the final yard must satisfy the rules.
 */
class StackingPropertyTest {

    /** Number of scenarios of the property test. */
    private static final int SCENARIOS = 200;

    @Test
    @DisplayName("La heurística produce planes válidos en 200 escenarios generados")
    void heuristicProducesValidPlans() {
        for (long seed = 1; seed <= SCENARIOS; seed++) {
            ScenarioGenerator.Scenario scenario = ScenarioGenerator.generate(seed, 4, 3, 6, 3, 20, 0.35);
            YardSnapshot planned = scenario.yardCopy();
            HeuristicPlanner planner = new HeuristicPlanner(OptimizerFixtures.scorer(planned), 3);

            PlacementPlan plan = planner.plan(planned, scenario.incoming(), System.nanoTime());

            assertThat(plan.moves().size() + plan.unplaced().size())
                    .as("seed %d: todos los contenedores deben estar ubicados o reportados", seed)
                    .isEqualTo(scenario.incoming().size());
            assertValidSequence(seed, scenario, planned, plan);
            assertNoFloatingContainers(seed, planned);
        }
    }

    @Test
    @DisplayName("La misma semilla produce el mismo plan")
    void theSameSeedProducesTheSamePlan() {
        for (long seed = 1; seed <= 5; seed++) {
            ScenarioGenerator.Scenario first = ScenarioGenerator.generate(seed, 4, 3, 6, 3, 15, 0.3);
            ScenarioGenerator.Scenario second = ScenarioGenerator.generate(seed, 4, 3, 6, 3, 15, 0.3);
            YardSnapshot firstYard = first.yardCopy();
            YardSnapshot secondYard = second.yardCopy();
            PlacementPlan firstPlan = new HeuristicPlanner(OptimizerFixtures.scorer(firstYard), 3)
                    .plan(firstYard, first.incoming(), System.nanoTime());
            PlacementPlan secondPlan = new HeuristicPlanner(OptimizerFixtures.scorer(secondYard), 3)
                    .plan(secondYard, second.incoming(), System.nanoTime());

            assertThat(firstPlan.moves()).hasSameSizeAs(secondPlan.moves());
            for (int i = 0; i < firstPlan.moves().size(); i++) {
                assertThat(firstPlan.moves().get(i).toSlotId())
                        .as("seed %d, movimiento %d", seed, i)
                        .isEqualTo(secondPlan.moves().get(i).toSlotId());
            }
            assertThat(firstPlan.metrics().expectedRestows()).isEqualTo(secondPlan.metrics().expectedRestows());
        }
    }

    private void assertValidSequence(long seed, ScenarioGenerator.Scenario scenario, YardSnapshot planned,
            PlacementPlan plan) {
        Map<String, ContainerUnit> units = new HashMap<>();
        for (ContainerUnit unit : scenario.incoming()) {
            units.put(unit.code(), unit);
        }
        YardSnapshot replay = scenario.yardCopy();
        for (Move move : plan.moves()) {
            ContainerUnit unit = units.get(move.containerCode());
            SlotNode slot = planned.slot(move.toSlotId()).orElseThrow();
            assertThat(unit)
                    .as("seed %d: el movimiento %d usa un contenedor del lote", seed, move.order())
                    .isNotNull();
            assertThat(PlacementRules.canPlace(replay, slot, unit))
                    .as("seed %d: movimiento %d inválido (%s en %s)", seed, move.order(), unit.code(), slot.code())
                    .isTrue();
            replay.place(slot, unit);
        }
    }

    private void assertNoFloatingContainers(long seed, YardSnapshot snapshot) {
        List<String> floating = new ArrayList<>();
        for (SlotNode slot : snapshot.slots()) {
            if (snapshot.containerAt(slot).isEmpty()) {
                continue;
            }
            if (slot.tier() > 1) {
                SlotNode below = snapshot.below(slot).orElse(null);
                if (below == null || snapshot.containerAt(below).isEmpty()) {
                    floating.add(slot.code());
                }
            }
        }
        assertThat(floating).as("seed %d: contenedores sin apoyo", seed).isEmpty();
    }
}
