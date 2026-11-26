package cr.logisticaportuaria.optimizer.application;

import cr.logisticaportuaria.optimizer.application.dto.OptimizerRequest;
import cr.logisticaportuaria.optimizer.application.dto.OptimizerResponse;
import cr.logisticaportuaria.optimizer.application.dto.RecommendationDto;
import cr.logisticaportuaria.optimizer.application.dto.YardMetricsResponse;
import cr.logisticaportuaria.optimizer.domain.ContainerUnit;
import cr.logisticaportuaria.optimizer.domain.HeuristicPlanner;
import cr.logisticaportuaria.optimizer.domain.Recommendation;
import cr.logisticaportuaria.optimizer.domain.SlotScorer;
import cr.logisticaportuaria.optimizer.domain.YardMetrics;
import cr.logisticaportuaria.optimizer.domain.YardSnapshot;
import cr.logisticaportuaria.optimizer.infrastructure.persistence.YardSnapshotLoader;
import cr.logisticaportuaria.shared.api.BusinessRuleException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service of the stacking recommendations.
 *
 * <p>The gate calls it when a container arrives: it loads the yard, scores every feasible
 * slot and answers the Top-N with an explanation in Spanish. The whole computation happens
 * in memory after a single read of the yard, which is what keeps the p95 under 200 ms with
 * 5 000 slots (see {@code docs/experiments/EXP-004-stacking.md}).
 */
@Service
public class OptimizerService {

    /** Code used when the request does not carry a container code. */
    private static final String UNKNOWN_CODE = "CONTENEDOR-NUEVO";

    private final YardSnapshotLoader loader;
    private final OptimizerProperties properties;

    /**
     * Constructor.
     *
     * @param loader     loader of the yard snapshot
     * @param properties configuration of the optimizer
     */
    public OptimizerService(YardSnapshotLoader loader, OptimizerProperties properties) {
        this.loader = loader;
        this.properties = properties;
    }

    /**
     * Top-N slots for an incoming container.
     *
     * @param request characteristics of the container
     * @return the recommendations, best first
     * @throws BusinessRuleException when no slot of the yard accepts the container
     */
    @Transactional(readOnly = true)
    public OptimizerResponse recommend(OptimizerRequest request) {
        long start = System.nanoTime();
        YardSnapshot snapshot = loader.load();
        ContainerUnit unit = toUnit(request);
        SlotScorer scorer = HeuristicPlanner.scorer(properties.toStackingWeights(), snapshot, Instant.now());
        List<Recommendation> recommendations = new HeuristicPlanner(scorer, properties.getTopRecommendations())
                .recommend(snapshot, unit);
        if (recommendations.isEmpty()) {
            throw new BusinessRuleException(
                    "No hay ningún slot compatible con el contenedor %s (%d pies, %s%s). Revise la zona del patio."
                            .formatted(unit.code(), unit.sizeFt(), unit.type(),
                                    unit.needsPower() ? ", con toma eléctrica" : ""));
        }
        long computationMs = Math.max(0, (System.nanoTime() - start) / 1_000_000);
        return new OptimizerResponse("HEURISTIC", computationMs, recommendations.stream()
                .map(recommendation -> RecommendationDto.from(recommendation, null))
                .toList());
    }

    /**
     * @return the current indicators of the yard
     */
    @Transactional(readOnly = true)
    public YardMetricsResponse metrics() {
        YardSnapshot snapshot = loader.load();
        return YardMetricsResponse.from(properties.getYardId(), YardMetrics.of(snapshot));
    }

    /**
     * @param request request of the API
     * @return the domain container
     */
    static ContainerUnit toUnit(OptimizerRequest request) {
        String code = request.containerCode() == null || request.containerCode().isBlank()
                ? UNKNOWN_CODE
                : request.containerCode();
        return ContainerUnit.incoming(
                code,
                request.sizeFt(),
                request.containerType(),
                request.weightKg(),
                request.etd(),
                request.etdUncertaintyHours(),
                request.clientCode(),
                Boolean.TRUE.equals(request.requiresPower()),
                null);
    }
}
