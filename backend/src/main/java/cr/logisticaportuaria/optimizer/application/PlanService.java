package cr.logisticaportuaria.optimizer.application;

import cr.logisticaportuaria.optimizer.application.dto.OptimizerPlanDto;
import cr.logisticaportuaria.optimizer.application.dto.PlanRequest;
import cr.logisticaportuaria.optimizer.application.dto.PlanStrategy;
import cr.logisticaportuaria.optimizer.domain.ContainerUnit;
import cr.logisticaportuaria.optimizer.domain.CpSatPlanner;
import cr.logisticaportuaria.optimizer.domain.HeuristicPlanner;
import cr.logisticaportuaria.optimizer.domain.PlacementPlan;
import cr.logisticaportuaria.optimizer.domain.SlotScorer;
import cr.logisticaportuaria.optimizer.domain.YardSnapshot;
import cr.logisticaportuaria.optimizer.infrastructure.persistence.YardSnapshotLoader;
import cr.logisticaportuaria.shared.api.BusinessRuleException;
import cr.logisticaportuaria.shared.domain.UuidV7;
import cr.logisticaportuaria.yard.domain.Container;
import cr.logisticaportuaria.yard.infrastructure.persistence.ContainerRepository;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Asynchronous stacking plans ({@code POST /api/v1/optimizer/plans}).
 *
 * <p>A plan is computed in a worker thread so the gate never blocks: the endpoint answers
 * {@code 202} with the identifier and the supervisor polls
 * {@code GET /api/v1/optimizer/plans/{planId}} until the status is {@code DONE} or
 * {@code FAILED}. The plans are kept in memory (the last 200); they are operational data,
 * not part of the domain, so they do not need a table.
 */
@Service
public class PlanService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlanService.class);

    /** Maximum number of plans kept in memory. */
    private static final int MAX_STORED_PLANS = 200;

    private final YardSnapshotLoader loader;
    private final ContainerRepository containerRepository;
    private final OptimizerProperties properties;
    private final Map<UUID, OptimizerPlanDto> plans = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, OptimizerPlanDto> eldest) {
                    return size() > MAX_STORED_PLANS;
                }
            });
    private final AtomicInteger threadSequence = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "optimizer-plan-" + threadSequence.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Constructor.
     *
     * @param loader                 loader of the yard snapshot
     * @param containerRepository    repository of the containers
     * @param properties             configuration of the optimizer
     */
    public PlanService(YardSnapshotLoader loader, ContainerRepository containerRepository,
            OptimizerProperties properties) {
        this.loader = loader;
        this.containerRepository = containerRepository;
        this.properties = properties;
    }

    /**
     * Accepts a plan and starts it in the background.
     *
     * @param request codes of the containers to place
     * @return the plan in {@code PENDING}
     * @throws BusinessRuleException when a code does not exist in the catalog
     */
    public OptimizerPlanDto submit(PlanRequest request) {
        List<Container> containers = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String raw : request.containerCodes()) {
            String code = normalize(raw);
            containerRepository.findByCode(code).ifPresentOrElse(containers::add, () -> unknown.add(raw));
        }
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException(
                    "No existen estos contenedores en el catálogo: %s.".formatted(String.join(", ", unknown)));
        }
        PlanStrategy strategy = request.strategy() == null ? PlanStrategy.HEURISTIC : request.strategy();
        int timeLimit = request.timeLimitSeconds() == null
                ? properties.getDefaultTimeLimitSeconds()
                : request.timeLimitSeconds();
        UUID planId = UuidV7.random();
        OptimizerPlanDto pending = OptimizerPlanDto.pending(planId, strategy.name(), timeLimit);
        plans.put(planId, pending);
        executor.submit(() -> run(planId, containers, strategy, timeLimit));
        return pending;
    }

    /**
     * @param planId identifier of the plan
     * @return the plan, when it exists
     */
    public Optional<OptimizerPlanDto> find(UUID planId) {
        return Optional.ofNullable(plans.get(planId));
    }

    /**
     * Stops the worker threads when the application shuts down.
     */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void run(UUID planId, List<Container> containers, PlanStrategy strategy, int timeLimit) {
        plans.computeIfPresent(planId, (id, current) -> OptimizerPlanDto.running(current));
        try {
            YardSnapshot snapshot = loader.load();
            Set<String> placedCodes = snapshot.containers().stream()
                    .map(ContainerUnit::code)
                    .map(PlanService::normalize)
                    .collect(Collectors.toSet());
            List<ContainerUnit> incoming = containers.stream()
                    .map(PlanService::toUnit)
                    .filter(unit -> !placedCodes.contains(normalize(unit.code())))
                    .toList();
            int alreadyPlaced = containers.size() - incoming.size();
            long start = System.nanoTime();
            SlotScorer scorer = HeuristicPlanner.scorer(properties.toStackingWeights(), snapshot, Instant.now());
            PlacementPlan plan = switch (strategy) {
                case HEURISTIC -> new HeuristicPlanner(scorer, properties.getTopRecommendations())
                        .plan(snapshot, incoming, start);
                case CP_SAT -> new CpSatPlanner(scorer, properties.getMaxCandidates())
                        .plan(snapshot, incoming, timeLimit);
            };
            plans.put(planId, OptimizerPlanDto.done(planId, strategy.name(), timeLimit, plan, alreadyPlaced));
        } catch (RuntimeException exception) {
            LOGGER.error("El plan {} falló", planId, exception);
            plans.put(planId, OptimizerPlanDto.failed(planId, strategy.name(), timeLimit,
                    "No se pudo calcular el plan: " + exception.getMessage()));
        }
    }

    private static ContainerUnit toUnit(Container container) {
        return ContainerUnit.incoming(
                container.getCode(),
                container.getSizeFt(),
                container.getContainerType(),
                container.getWeightKg() == null ? null : container.getWeightKg().doubleValue(),
                container.getEtd(),
                null,
                container.getClientCode(),
                container.isRequiresPower(),
                container.getImoClass());
    }

    private static String normalize(String code) {
        return code == null ? "" : code.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }
}
