package cr.logisticaportuaria.optimizer.application.dto;

import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Request of {@code POST /api/v1/optimizer/plans}.
 *
 * @param containerCodes   BIC codes of the containers to place (1..200)
 * @param strategy         {@code HEURISTIC} (default) or {@code CP_SAT}
 * @param timeLimitSeconds time limit of the solver, in seconds (1..600)
 */
public record PlanRequest(
        @NotEmpty @Size(max = 200) List<@NotBlank String> containerCodes,
        PlanStrategy strategy,
        @Min(1) @Max(600) Integer timeLimitSeconds) {
}
