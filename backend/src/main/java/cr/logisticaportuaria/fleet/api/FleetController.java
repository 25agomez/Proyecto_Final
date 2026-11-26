package cr.logisticaportuaria.fleet.api;

import cr.logisticaportuaria.fleet.application.FleetQueryService;
import cr.logisticaportuaria.fleet.application.WorkOrderService;
import cr.logisticaportuaria.fleet.application.dto.AssignWorkOrderRequest;
import cr.logisticaportuaria.fleet.application.dto.CreateWorkOrderRequest;
import cr.logisticaportuaria.fleet.application.dto.FleetKpisResponse;
import cr.logisticaportuaria.fleet.application.dto.GeofenceResponse;
import cr.logisticaportuaria.fleet.application.dto.TrackResponse;
import cr.logisticaportuaria.fleet.application.dto.VehicleResponse;
import cr.logisticaportuaria.fleet.application.dto.WorkOrderResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API of the fleet: vehicles, track, KPIs, geofences and work orders.
 *
 * <p>{@code GET /fleet/vehicles} and {@code GET /fleet/vehicles/{id}/track} are part of the
 * OpenAPI contract of P1; the KPI, geofence and work order operations are additive
 * extensions declared in {@code OpenApiContractTest} (MEMORY.md §11).
 */
@RestController
@RequestMapping("/api/v1/fleet")
public class FleetController {

    private static final String READ_ROLES =
            "hasAnyRole('OPERADOR_PUERTA', 'SUPERVISOR', 'ADMIN', 'AUDITOR')";
    private static final String WRITE_ROLES =
            "hasAnyRole('OPERADOR_PUERTA', 'SUPERVISOR', 'ADMIN')";

    private static final MediaType GEO_JSON = MediaType.parseMediaType("application/geo+json");

    private final FleetQueryService queryService;
    private final WorkOrderService workOrderService;

    /**
     * Constructor.
     *
     * @param queryService     read queries
     * @param workOrderService lifecycle of the work orders
     */
    public FleetController(FleetQueryService queryService, WorkOrderService workOrderService) {
        this.queryService = queryService;
        this.workOrderService = workOrderService;
    }

    /**
     * @param yardId optional yard
     * @param status optional status
     * @return fleet with its last state
     */
    @GetMapping("/vehicles")
    @PreAuthorize(READ_ROLES)
    public List<VehicleResponse> vehicles(@RequestParam(required = false) UUID yardId,
            @RequestParam(required = false) String status) {
        return queryService.vehicles(yardId, status);
    }

    /**
     * @param id   identifier of the vehicle
     * @param from optional inclusive start
     * @param to   optional exclusive end
     * @return GeoJSON LineString of the track
     */
    @GetMapping(value = "/vehicles/{id}/track", produces = {"application/geo+json",
            "application/json"})
    @PreAuthorize(READ_ROLES)
    public ResponseEntity<TrackResponse> track(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok().contentType(GEO_JSON).body(queryService.track(id, from, to));
    }

    /**
     * @param yardId optional yard
     * @param from   optional inclusive start
     * @param to     optional exclusive end
     * @return KPIs of the fleet
     */
    @GetMapping("/kpis")
    @PreAuthorize(READ_ROLES)
    public FleetKpisResponse kpis(@RequestParam(required = false) UUID yardId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return queryService.kpis(yardId, from, to);
    }

    /**
     * @param yardId optional yard
     * @return geofences of the yard
     */
    @GetMapping("/geofences")
    @PreAuthorize(READ_ROLES)
    public List<GeofenceResponse> geofences(@RequestParam(required = false) UUID yardId) {
        return workOrderService.geofences(yardId);
    }

    /**
     * @param yardId optional yard
     * @param status optional status
     * @return work orders
     */
    @GetMapping("/work-orders")
    @PreAuthorize(READ_ROLES)
    public List<WorkOrderResponse> workOrders(@RequestParam(required = false) UUID yardId,
            @RequestParam(required = false) String status) {
        return workOrderService.list(yardId, status);
    }

    /**
     * @param request request of the order
     * @return the created order
     */
    @PostMapping("/work-orders")
    @PreAuthorize(WRITE_ROLES)
    public ResponseEntity<WorkOrderResponse> createWorkOrder(
            @RequestBody CreateWorkOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workOrderService.create(request));
    }

    /**
     * @param yardId optional yard
     * @return the order assigned
     */
    @PostMapping("/work-orders/assign-next")
    @PreAuthorize(WRITE_ROLES)
    public WorkOrderResponse assignNext(@RequestParam(required = false) UUID yardId) {
        return workOrderService.assignNext(yardId);
    }

    /**
     * @param id      identifier of the order
     * @param request optional vehicle
     * @return the order after the assignment
     */
    @PostMapping("/work-orders/{id}/assign")
    @PreAuthorize(WRITE_ROLES)
    public WorkOrderResponse assign(@PathVariable UUID id,
            @RequestBody(required = false) AssignWorkOrderRequest request) {
        return workOrderService.assign(id, request == null ? null : request.vehicleId());
    }

    /**
     * @param id identifier of the order
     * @return the order after the transition
     */
    @PostMapping("/work-orders/{id}/start")
    @PreAuthorize(WRITE_ROLES)
    public WorkOrderResponse start(@PathVariable UUID id) {
        return workOrderService.start(id);
    }

    /**
     * @param id identifier of the order
     * @return the order after the transition
     */
    @PostMapping("/work-orders/{id}/complete")
    @PreAuthorize(WRITE_ROLES)
    public WorkOrderResponse complete(@PathVariable UUID id) {
        return workOrderService.complete(id);
    }

    /**
     * @param id identifier of the order
     * @return the order after the transition
     */
    @PostMapping("/work-orders/{id}/cancel")
    @PreAuthorize(WRITE_ROLES)
    public WorkOrderResponse cancel(@PathVariable UUID id) {
        return workOrderService.cancel(id);
    }
}
