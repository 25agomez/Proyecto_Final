package cr.logisticaportuaria.optimizer.application.dto;

/**
 * Strategy of a stacking plan.
 */
public enum PlanStrategy {

    /** ETD-aware greedy heuristic: fast, used by the gate by default. */
    HEURISTIC,

    /** CP-SAT model of OR-Tools: slower, optimized as a whole. */
    CP_SAT
}
