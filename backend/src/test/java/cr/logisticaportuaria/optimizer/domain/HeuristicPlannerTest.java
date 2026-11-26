package cr.logisticaportuaria.optimizer.domain;

import static cr.logisticaportuaria.optimizer.domain.OptimizerFixtures.NOW;
import static cr.logisticaportuaria.optimizer.domain.OptimizerFixtures.dry;
import static cr.logisticaportuaria.optimizer.domain.OptimizerFixtures.drySlot;
import static cr.logisticaportuaria.optimizer.domain.OptimizerFixtures.hazmat;
import static cr.logisticaportuaria.optimizer.domain.OptimizerFixtures.reefer;
import static cr.logisticaportuaria.optimizer.domain.OptimizerFixtures.slot;
import static org.assertj.core.api.Assertions.assertThat;

import cr.logisticaportuaria.yard.domain.SlotType;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deterministic cases of the heuristic: the acceptance cases of P4 (simple stack, 40 ft
 * contiguous, reefer, hazmat) and the ETD-aware behaviour.
 */
class HeuristicPlannerTest {

    @Test
    @DisplayName("Prefiere el nivel superior cercano antes que un slot lejano de la misma altura")
    void prefersTheCloserUpperTierOverAFarBase() {
        SlotNode nearBase = drySlot("A", 1, 1, 1, 20);
        SlotNode nearUpper = drySlot("A", 1, 1, 2, 20);
        SlotNode farBase = drySlot("C", 1, 1, 1, 20);
        YardSnapshot yard = YardSnapshot.of(List.of(nearBase, nearUpper, farBase), List.of());
        yard.place(nearBase, dry("LATE0000001", 20, 10_000, NOW.plus(30, ChronoUnit.DAYS), "ACME"));

        HeuristicPlanner planner = new HeuristicPlanner(OptimizerFixtures.scorer(yard), 3);
        ContainerUnit incoming = dry("EARLY000001", 20, 9_000, NOW.plus(1, ChronoUnit.DAYS), "ACME");

        List<Recommendation> recommendations = planner.recommend(yard, incoming);

        assertThat(recommendations).isNotEmpty();
        assertThat(recommendations.get(0).slot()).isEqualTo(nearUpper);
    }

    @Test
    @DisplayName("No apila un contenedor sobre otro que sale antes (restow)")
    void avoidsBuryingAnEarlierDeparture() {
        SlotNode nearBase = drySlot("A", 1, 1, 1, 20);
        SlotNode nearUpper = drySlot("A", 1, 1, 2, 20);
        SlotNode farBase = drySlot("C", 1, 1, 1, 20);
        YardSnapshot yard = YardSnapshot.of(List.of(nearBase, nearUpper, farBase), List.of());
        yard.place(nearBase, dry("EARLY0000001", 20, 10_000, NOW.plus(1, ChronoUnit.DAYS), "ACME"));

        HeuristicPlanner planner = new HeuristicPlanner(OptimizerFixtures.scorer(yard), 3);
        ContainerUnit incoming = dry("LATER000001", 20, 9_000, NOW.plus(10, ChronoUnit.DAYS), "ACME");

        List<Recommendation> recommendations = planner.recommend(yard, incoming);

        assertThat(recommendations.get(0).slot()).isEqualTo(farBase);
    }

    @Test
    @DisplayName("Un contenedor de 40 pies solo recibe slots de 40 pies")
    void fortyFootOnlyGoesToFortyFootSlots() {
        SlotNode twenty = drySlot("A", 11, 1, 1, 20);
        SlotNode forty = drySlot("A", 1, 1, 1, 40);
        YardSnapshot yard = YardSnapshot.of(List.of(twenty, forty), List.of());

        List<Recommendation> recommendations = new HeuristicPlanner(OptimizerFixtures.scorer(yard), 3)
                .recommend(yard, dry("FORTY000001", 40, 20_000, NOW.plus(2, ChronoUnit.DAYS), "ACME"));

        assertThat(recommendations).isNotEmpty();
        assertThat(recommendations).allMatch(recommendation -> recommendation.slot().sizeFt() == 40);
        assertThat(recommendations.get(0).slot()).isEqualTo(forty);
    }

    @Test
    @DisplayName("Un reefer exige toma eléctrica y un hazmat exige zona segregada")
    void respectsReeferPowerAndHazmatSegregation() {
        SlotNode dry = drySlot("B", 1, 1, 1, 20);
        SlotNode reeferSlot = slot("A", 1, 1, 1, 20, SlotType.reefer, true);
        SlotNode hazmatSlot = slot("F", 1, 1, 1, 20, SlotType.hazmat, false);
        YardSnapshot yard = YardSnapshot.of(List.of(dry, reeferSlot, hazmatSlot), List.of());
        HeuristicPlanner planner = new HeuristicPlanner(OptimizerFixtures.scorer(yard), 3);

        assertThat(planner.recommend(yard, reefer("REEFER00001", 20, NOW.plus(3, ChronoUnit.DAYS))))
                .extracting(Recommendation::slot)
                .containsExactly(reeferSlot);
        assertThat(planner.recommend(yard, hazmat("HAZMAT00001", 20, NOW.plus(3, ChronoUnit.DAYS))))
                .extracting(Recommendation::slot)
                .containsExactly(hazmatSlot);
        assertThat(planner.recommend(yard, dry("DRY00000001", 20, 9_000, NOW.plus(3, ChronoUnit.DAYS), "ACME")))
                .extracting(Recommendation::slot)
                .containsExactly(dry);
    }

    @Test
    @DisplayName("Sin slot compatible no hay recomendaciones")
    void returnsNoRecommendationsWhenThereIsNoFeasibleSlot() {
        SlotNode dry = drySlot("B", 1, 1, 1, 20);
        YardSnapshot yard = YardSnapshot.of(List.of(dry), List.of());
        ContainerUnit impossible = ContainerUnit.incoming("IMPOSSIBLE1", 20,
                cr.logisticaportuaria.yard.domain.ContainerType.hazmat, 20_000.0, NOW.plus(2, ChronoUnit.DAYS),
                4.0, "ACME", true, "3");

        List<Recommendation> recommendations = new HeuristicPlanner(OptimizerFixtures.scorer(yard), 3)
                .recommend(yard, impossible);

        assertThat(recommendations).isEmpty();
    }

    @Test
    @DisplayName("La explicación está en español y nombra el bloque y la distancia")
    void explainsTheRecommendationInSpanish() {
        SlotNode base = drySlot("B", 3, 2, 1, 20);
        YardSnapshot yard = YardSnapshot.of(List.of(base), List.of());

        Recommendation recommendation = new HeuristicPlanner(OptimizerFixtures.scorer(yard), 1)
                .recommend(yard, dry("EXPLAIN0001", 20, 9_000, NOW.plus(2, ChronoUnit.DAYS), "ACME")).get(0);

        assertThat(recommendation.explanation())
                .contains("Bloque B")
                .contains("bahía 3")
                .contains("fila 2")
                .contains("nivel 1")
                .contains("m de la puerta")
                .doesNotContain("null");
        assertThat(recommendation.rank()).isEqualTo(1);
        assertThat(recommendation.score()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Un contenedor más pesado nunca se apila sobre uno más liviano")
    void neverStacksHeavierOnLighter() {
        SlotNode base = drySlot("A", 1, 1, 1, 20);
        SlotNode upper = drySlot("A", 1, 1, 2, 20);
        YardSnapshot yard = YardSnapshot.of(List.of(base, upper), List.of());
        yard.place(base, dry("LIGHT000001", 20, 5_000, NOW.plus(20, ChronoUnit.DAYS), "ACME"));

        List<Recommendation> recommendations = new HeuristicPlanner(OptimizerFixtures.scorer(yard), 3)
                .recommend(yard, dry("HEAVY000001", 20, 25_000, NOW.plus(1, ChronoUnit.DAYS), "ACME"));

        assertThat(recommendations).isEmpty();
    }
}
