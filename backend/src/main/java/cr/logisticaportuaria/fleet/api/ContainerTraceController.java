package cr.logisticaportuaria.fleet.api;

import cr.logisticaportuaria.fleet.application.FleetQueryService;
import cr.logisticaportuaria.fleet.application.dto.ContainerTraceResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trace of a container across the gate, the yard and the fleet.
 *
 * <p>Additive extension of the contract of P1 (MEMORY.md §11).
 */
@RestController
@RequestMapping("/api/v1/containers")
public class ContainerTraceController {

    private static final String READ_ROLES =
            "hasAnyRole('OPERADOR_PUERTA', 'SUPERVISOR', 'ADMIN', 'AUDITOR')";

    private final FleetQueryService queryService;

    /**
     * Constructor.
     *
     * @param queryService read queries of the fleet
     */
    public ContainerTraceController(FleetQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * @param code container code
     * @return trace of the container, or 404 when it does not exist
     */
    @GetMapping("/{code}/trace")
    @PreAuthorize(READ_ROLES)
    public ContainerTraceResponse trace(@PathVariable String code) {
        return queryService.trace(code);
    }
}
