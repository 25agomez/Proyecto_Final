package cr.logisticaportuaria.optimizer.domain;

import static cr.logisticaportuaria.optimizer.domain.OptimizerFixtures.NOW;
import static cr.logisticaportuaria.optimizer.domain.OptimizerFixtures.dry;
import static cr.logisticaportuaria.optimizer.domain.OptimizerFixtures.drySlot;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of the CP-SAT planner: correctness of the plan, time limit and infeasibility.
 */
class CpSatPlannerTest {

    @Test
    @DisplayName("Coloca todos los contenedores y respeta las reglas físicas")
    void placesEverythingRespectingTheRules() {
        ScenarioGenerator.Scenario scenario = ScenarioGenerator.generate(7L, 5, 3, 8, 3, 12, 0.35);
        YardSnapshot yard = scenario.yardCopy();
        CpSatPlanner planner = new CpSatPlanner(OptimizerFixtures.scorer(yard), 32);

        PlacementPlan plan = planner.plan(yard, scenario.incoming(), 30);

        assertThat(plan.solverStatus()).isIn("OPTIMAL", "FEASIBLE");
        assertThat(plan.unplaced()).isEmpty();
        assertThat(plan.moves()).hasSize(scenario.incoming().size());
        assertThat(plan.metrics().expectedRestows()).isGreaterThanOrEqualTo(0.0);
        assertValidPlan(scenario, yard, plan);
    }

    @Test
    @DisplayName("Respeta timeLimitSeconds y devuelve un plan FEASIBLE en una instancia grande")
    void respectsTheTimeLimit() {
        ScenarioGenerator.Scenario scenario = ScenarioGenerator.generate(11L, 10, 5, 25, 4, 300, 0.30);
        YardSnapshot yard = scenario.yardCopy();
        CpSatPlanner planner = new CpSatPlanner(OptimizerFixtures.scorer(yard), 24);

        long start = System.nanoTime();
        PlacementPlan plan = planner.plan(yard, scenario.incoming(), 2);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("el solver debe respetar el límite de 2 s")
                .isLessThan(6_000);
        assertThat(plan.solverStatus()).isIn("OPTIMAL", "FEASIBLE");
        assertThat(plan.moves()).isNotEmpty();
        assertThat(plan.computationMs()).isLessThan(6_000);
    }

    @Test
    @DisplayName("Sin sitio para el lote coloca lo que cabe y reporta el resto como no ubicado")
    void reportsUnplacedContainersWhenThereIsNoRoom() {
        SlotNode onlySlot = drySlot("A", 1, 1, 1, 20);
        YardSnapshot yard = YardSnapshot.of(List.of(onlySlot), List.of());
        CpSatPlanner planner = new CpSatPlanner(OptimizerFixtures.scorer(yard), 8);
        List<ContainerUnit> incoming = new ArrayList<>();
        incoming.add(dry("FIRST000001", 20, 9_000, NOW.plus(1, ChronoUnit.DAYS), "ACME"));
        incoming.add(dry("SECOND00001", 20, 9_000, NOW.plus(2, ChronoUnit.DAYS), "ACME"));

        PlacementPlan plan = planner.plan(yard, incoming, 5);

        assertThat(plan.solverStatus()).isIn("OPTIMAL", "FEASIBLE");
        assertThat(plan.moves()).hasSize(1);
        assertThat(plan.unplaced()).hasSize(1);
        assertThat(plan.moves().get(0).toSlotId()).isEqualTo(onlySlot.id());
        assertThat(yard.containerAt(onlySlot)).isPresent();
    }

    @Test
    @DisplayName("Sin contenedores devuelve un plan vacío óptimo")
    void handlesAnEmptyRequest() {
        YardSnapshot yard = YardSnapshot.of(List.of(drySlot("A", 1, 1, 1, 20)), List.of());

        PlacementPlan plan = new CpSatPlanner(OptimizerFixtures.scorer(yard), 8).plan(yard, List.of(), 5);

        assertThat(plan.moves()).isEmpty();
        assertThat(plan.unplaced()).isEmpty();
        assertThat(plan.solverStatus()).isEqualTo("OPTIMAL");
    }

    @Test
    @DisplayName("Con una sola hebra de búsqueda el resultado es determinista")
    void isDeterministic() {
        ScenarioGenerator.Scenario scenario = ScenarioGenerator.generate(21L, 3, 3, 6, 3, 8, 0.3);
        YardSnapshot firstYard = scenario.yardCopy();
        YardSnapshot secondYard = scenario.yardCopy();
        CpSatPlanner planner = new CpSatPlanner(OptimizerFixtures.scorer(firstYard), 32);

        List<Move> first = planner.plan(firstYard, scenario.incoming(), 10).moves();
        List<Move> second = planner.plan(secondYard, scenario.incoming(), 10).moves();

        assertThat(first).hasSameSizeAs(second);
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).toSlotId()).isEqualTo(second.get(i).toSlotId());
        }
    }

    private void assertValidPlan(ScenarioGenerator.Scenario scenario, YardSnapshot planned, PlacementPlan plan) {
        YardSnapshot replay = scenario.yardCopy();
        for (Move move : plan.moves()) {
            ContainerUnit unit = scenario.incoming().stream()
                    .filter(candidate -> candidate.code().equals(move.containerCode()))
                    .findFirst()
                    .orElseThrow();
            SlotNode slot = planned.slot(move.toSlotId()).orElseThrow();
            assertThat(PlacementRules.canPlace(replay, slot, unit))
                    .as("movimiento válido para %s en %s", unit.code(), slot.code())
                    .isTrue();
            replay.place(slot, unit);
        }
        for (SlotNode slot : replay.slots()) {
            if (replay.containerAt(slot).isPresent() && slot.tier() > 1) {
                assertThat(replay.containerAt(replay.below(slot).orElseThrow()))
                        .as("apoyo de %s", slot.code())
                        .isPresent();
            }
        }
    }
}
