package cr.logisticaportuaria.optimizer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Performance test of the gate decision: the heuristic must answer the Top-3 for one
 * container in less than 200 ms (p95) with 5 000 slots.
 *
 * <p>The measured value is printed so it can be copied into
 * {@code docs/experiments/EXP-004-stacking.md}.
 */
class HeuristicPerformanceTest {

    /** Slots of the yard: 10 blocks x 5 rows x 25 bays x 4 tiers. */
    private static final int BLOCKS = 10;
    private static final int ROWS = 5;
    private static final int BAYS = 25;
    private static final int TIERS = 4;

    @Test
    @DisplayName("p95 de /recommendations menor a 200 ms con 5 000 slots")
    void p95StaysUnder200MsWithFiveThousandSlots() {
        ScenarioGenerator.Scenario scenario = ScenarioGenerator.generate(99L, BLOCKS, ROWS, BAYS, TIERS, 0, 0.30);
        assertThat(scenario.yard().totalSlots()).isEqualTo(5_000);
        YardSnapshot yard = scenario.yardCopy();
        HeuristicPlanner planner = new HeuristicPlanner(OptimizerFixtures.scorer(yard), 3);
        Random random = new Random(42);

        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < 220; i++) {
            ContainerUnit unit = ScenarioGenerator.incoming("PERF", i, random);
            long start = System.nanoTime();
            List<Recommendation> recommendations = planner.recommend(yard, unit);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(recommendations).as("siempre debe haber un slot compatible en este escenario").isNotEmpty();
            if (i >= 20) {
                samples.add(elapsedMs);
            }
        }
        Collections.sort(samples);
        long p50 = samples.get(samples.size() / 2);
        long p95 = samples.get((int) Math.ceil(samples.size() * 0.95) - 1);
        long p99 = samples.get((int) Math.ceil(samples.size() * 0.99) - 1);
        System.out.printf("[P4] Top-3 con 5 000 slots — p50=%d ms, p95=%d ms, p99=%d ms, n=%d%n",
                p50, p95, p99, samples.size());

        assertThat(p95).isLessThan(200);
    }
}
