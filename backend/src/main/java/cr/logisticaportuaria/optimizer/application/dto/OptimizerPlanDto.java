package cr.logisticaportuaria.optimizer.application.dto;

import cr.logisticaportuaria.optimizer.domain.PlacementPlan;
import java.util.List;
import java.util.UUID;

/**
 * Asynchronous stacking plan ({@code OptimizerPlan} of the contract plus the operational
 * details the supervisor needs).
 *
 * @param planId           identifier of the plan
 * @param status           {@code PENDING}, {@code RUNNING}, {@code DONE} or {@code FAILED}
 * @param strategy         strategy requested
 * @param timeLimitSeconds time limit applied to the solver
 * @param solverStatus     status reported by the solver (OPTIMAL, FEASIBLE, ...)
 * @param computationMs    time spent computing the plan
 * @param moves            steps to execute, in order
 * @param metrics          indicators of the yard after the plan
 * @param unplaced         containers that could not be placed
 * @param alreadyPlaced    requested containers that were already in the yard
 * @param message          message in Spanish for the operator
 */
public record OptimizerPlanDto(
        UUID planId,
        String status,
        String strategy,
        Integer timeLimitSeconds,
        String solverStatus,
        Long computationMs,
        List<MoveDto> moves,
        PlanMetricsDto metrics,
        List<String> unplaced,
        Integer alreadyPlaced,
        String message) {

    /**
     * Canonical constructor: the lists are copied so the plan is immutable and can be
     * shared between the worker thread and the HTTP threads.
     */
    public OptimizerPlanDto {
        moves = moves == null ? List.of() : List.copyOf(moves);
        unplaced = unplaced == null ? List.of() : List.copyOf(unplaced);
    }

    /**
     * @param planId           identifier of the plan
     * @param strategy         strategy requested
     * @param timeLimitSeconds time limit applied
     * @return a plan that has been accepted but not started
     */
    public static OptimizerPlanDto pending(UUID planId, String strategy, int timeLimitSeconds) {
        return new OptimizerPlanDto(planId, "PENDING", strategy, timeLimitSeconds, null, null, List.of(), null,
                List.of(), 0, "El plan se está calculando.");
    }

    /**
     * @param plan plan accepted
     * @return the same plan marked as running
     */
    public static OptimizerPlanDto running(OptimizerPlanDto plan) {
        return new OptimizerPlanDto(plan.planId(), "RUNNING", plan.strategy(), plan.timeLimitSeconds(), null, null,
                List.of(), null, List.of(), 0, "El plan se está calculando.");
    }

    /**
     * @param planId           identifier of the plan
     * @param strategy         strategy used
     * @param timeLimitSeconds time limit applied
     * @param computed         result of the solver
     * @param alreadyPlaced    requested containers that were already in the yard
     * @return the finished plan
     */
    public static OptimizerPlanDto done(UUID planId, String strategy, int timeLimitSeconds, PlacementPlan computed,
            int alreadyPlaced) {
        String message = computed.unplaced().isEmpty()
                ? "%d contenedor(es) ubicado(s) con la estrategia %s.".formatted(computed.placed(), strategy)
                : "%d contenedor(es) ubicado(s) y %d sin slot compatible con la estrategia %s."
                        .formatted(computed.placed(), computed.unplaced().size(), strategy);
        if (alreadyPlaced > 0) {
            message += " %d ya estaban en el patio.".formatted(alreadyPlaced);
        }
        return new OptimizerPlanDto(
                planId,
                "DONE",
                strategy,
                timeLimitSeconds,
                computed.solverStatus(),
                computed.computationMs(),
                computed.moves().stream().map(MoveDto::from).toList(),
                PlanMetricsDto.from(computed.metrics()),
                computed.unplaced(),
                alreadyPlaced,
                message);
    }

    /**
     * @param planId           identifier of the plan
     * @param strategy         strategy requested
     * @param timeLimitSeconds time limit applied
     * @param message          reason of the failure, in Spanish
     * @return the failed plan
     */
    public static OptimizerPlanDto failed(UUID planId, String strategy, int timeLimitSeconds, String message) {
        return new OptimizerPlanDto(planId, "FAILED", strategy, timeLimitSeconds, null, null, List.of(), null,
                List.of(), 0, message);
    }
}
