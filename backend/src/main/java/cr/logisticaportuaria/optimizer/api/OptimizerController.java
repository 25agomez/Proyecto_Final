package cr.logisticaportuaria.optimizer.api;

import cr.logisticaportuaria.optimizer.application.OptimizerService;
import cr.logisticaportuaria.optimizer.application.PlanService;
import cr.logisticaportuaria.optimizer.application.dto.OptimizerPlanDto;
import cr.logisticaportuaria.optimizer.application.dto.OptimizerRequest;
import cr.logisticaportuaria.optimizer.application.dto.OptimizerResponse;
import cr.logisticaportuaria.optimizer.application.dto.PlanRequest;
import cr.logisticaportuaria.optimizer.application.dto.YardMetricsResponse;
import cr.logisticaportuaria.shared.api.NotFoundException;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API of the stacking optimizer.
 *
 * <p>Implements the two operations of the contract ({@code POST /recommendations} and
 * {@code POST /plans}) plus two extensions marked as such in {@code MEMORY.md}: the
 * consultation of an asynchronous plan and the current metrics of the yard.
 */
@RestController
@RequestMapping("/api/v1/optimizer")
public class OptimizerController {

    private static final String ROLES = "hasAnyRole('OPERADOR_PUERTA', 'SUPERVISOR', 'ADMIN')";

    private static final String READ_ROLES = "hasAnyRole('OPERADOR_PUERTA', 'SUPERVISOR', 'ADMIN', 'AUDITOR')";

    private final OptimizerService optimizerService;
    private final PlanService planService;

    /**
     * Constructor.
     *
     * @param optimizerService service of the recommendations
     * @param planService      service of the asynchronous plans
     */
    public OptimizerController(OptimizerService optimizerService, PlanService planService) {
        this.optimizerService = optimizerService;
        this.planService = planService;
    }

    /**
     * Top-3 slots for an incoming container, with an explanation in Spanish.
     *
     * @param request characteristics of the container
     * @return the recommendations
     */
    @PostMapping("/recommendations")
    @PreAuthorize(ROLES)
    public OptimizerResponse recommendations(@Valid @RequestBody OptimizerRequest request) {
        return optimizerService.recommend(request);
    }

    /**
     * Starts an asynchronous batch plan.
     *
     * @param request codes of the containers and strategy
     * @return {@code 202} with the plan in {@code PENDING}
     */
    @PostMapping("/plans")
    @PreAuthorize(ROLES)
    public ResponseEntity<OptimizerPlanDto> createPlan(@Valid @RequestBody PlanRequest request) {
        return ResponseEntity.accepted().body(planService.submit(request));
    }

    /**
     * Returns the status of a plan (extension of the contract).
     *
     * @param planId identifier of the plan
     * @return the plan
     */
    @GetMapping("/plans/{planId}")
    @PreAuthorize(READ_ROLES)
    public OptimizerPlanDto plan(@PathVariable UUID planId) {
        return planService.find(planId).orElseThrow(() -> new NotFoundException("plan", planId));
    }

    /**
     * Current indicators of the yard (extension of the contract).
     *
     * @return the metrics
     */
    @GetMapping("/metrics")
    @PreAuthorize(READ_ROLES)
    public YardMetricsResponse metrics() {
        return optimizerService.metrics();
    }
}
