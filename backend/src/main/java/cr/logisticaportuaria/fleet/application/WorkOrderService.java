package cr.logisticaportuaria.fleet.application;

import cr.logisticaportuaria.fleet.application.dto.CreateWorkOrderRequest;
import cr.logisticaportuaria.fleet.application.dto.GeofenceResponse;
import cr.logisticaportuaria.fleet.application.dto.WorkOrderResponse;
import cr.logisticaportuaria.fleet.domain.FleetStatus;
import cr.logisticaportuaria.fleet.domain.Geofence;
import cr.logisticaportuaria.fleet.domain.TrackGeometry;
import cr.logisticaportuaria.fleet.domain.YardLayout;
import cr.logisticaportuaria.fleet.infrastructure.persistence.FleetVehicleRepository;
import cr.logisticaportuaria.fleet.infrastructure.persistence.WorkOrderRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lifecycle of the work orders and assignment of the vehicles.
 *
 * <p>
 * Assignment heuristic: the order with the lowest {@code priority} value (more
 * urgent)
 * and then the oldest is dispatched first, and it is given the free vehicle
 * closest to the
 * origin slot (or to the destination, or to the gate zone when the order has no
 * slots).
 * Distance is the euclidean distance in the local metric frame (ADR-013).
 */
@Service
public class WorkOrderService {

    private static final Set<String> ORDER_TYPES = Set.of("INGRESO", "RETIRO", "REUBICACION", "MOVIMIENTO");

    /** Centroid of the gate zone, used when the order has no slot. */
    private static final double GATE_X = 60.0;
    private static final double GATE_Y = 20.0;

    private final WorkOrderRepository repository;
    private final FleetVehicleRepository vehicleRepository;
    private final FleetQueryService queryService;
    private final GeofenceCache geofenceCache;
    private final FleetStateCache stateCache;

    /**
     * Constructor.
     *
     * @param repository        repository of work orders
     * @param vehicleRepository repository of vehicles
     * @param queryService      read queries of the module
     * @param geofenceCache     cache of geofences
     * @param stateCache        cache of the last states
     */
    public WorkOrderService(WorkOrderRepository repository,
            FleetVehicleRepository vehicleRepository, FleetQueryService queryService,
            GeofenceCache geofenceCache, FleetStateCache stateCache) {
        this.repository = repository;
        this.vehicleRepository = vehicleRepository;
        this.queryService = queryService;
        this.geofenceCache = geofenceCache;
        this.stateCache = stateCache;
    }

    /**
     * Creates a work order in {@code PENDIENTE}.
     *
     * @param request request of the order
     * @return the created order
     */
    @Transactional
    public WorkOrderResponse create(CreateWorkOrderRequest request) {
        UUID yardId = queryService.resolveYard(request.yardId());
        String orderType = request.orderType() == null ? "MOVIMIENTO"
                : request.orderType().toUpperCase(Locale.ROOT);
        if (!ORDER_TYPES.contains(orderType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "orderType must be one of " + ORDER_TYPES);
        }
        int priority = request.priority() == null ? 5 : request.priority();
        if (priority < 1 || priority > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "priority must be between 1 and 10");
        }
        String container = request.containerCode() == null ? null
                : request.containerCode().replace(" ", "").toUpperCase(Locale.ROOT);
        UUID id = repository.create(yardId, container, orderType, request.fromSlotId(),
                request.toSlotId(), priority);
        return WorkOrderResponse.from(find(id));
    }

    /**
     * @param yardId optional yard
     * @param status optional status
     * @return orders of the yard, newest first
     */
    public List<WorkOrderResponse> list(UUID yardId, String status) {
        UUID yard = queryService.resolveYard(yardId);
        return repository.findByYard(yard, status).stream().map(WorkOrderResponse::from).toList();
    }

    /**
     * Assigns a vehicle to a pending order.
     *
     * @param orderId   identifier of the order
     * @param vehicleId optional vehicle; null means the nearest free vehicle
     * @return the order after the assignment
     */
    @Transactional
    public WorkOrderResponse assign(UUID orderId, UUID vehicleId) {
        WorkOrderRepository.WorkOrderRow order = find(orderId);
        requireStatus(order, WorkOrderRepository.PENDIENTE);
        UUID selected = vehicleId != null ? requireFreeVehicle(order, vehicleId)
                : nearestFreeVehicle(order);
        repository.assign(orderId, selected);
        vehicleRepository.updateStatus(selected, FleetStatus.EN_MOVIMIENTO);
        return WorkOrderResponse.from(find(orderId));
    }

    /**
     * Assigns the most urgent pending order of the yard to its nearest free
     * vehicle.
     *
     * @param yardId optional yard
     * @return the order after the assignment, or 404 when there is no pending order
     */
    @Transactional
    public WorkOrderResponse assignNext(UUID yardId) {
        UUID yard = queryService.resolveYard(yardId);
        List<WorkOrderRepository.WorkOrderRow> pending = new ArrayList<>(
                repository.findByYard(yard, WorkOrderRepository.PENDIENTE));
        Optional<WorkOrderRepository.WorkOrderRow> next = pending.stream()
                .min(Comparator.comparingInt((WorkOrderRepository.WorkOrderRow row) -> row.priority())
                        .thenComparing(row -> row.createdAt()));
        if (next.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no pending work orders");
        }
        return assign(next.get().id(), null);
    }

    /**
     * Starts an assigned order.
     *
     * @param orderId identifier of the order
     * @return the order after the transition
     */
    @Transactional
    public WorkOrderResponse start(UUID orderId) {
        WorkOrderRepository.WorkOrderRow order = find(orderId);
        requireStatus(order, WorkOrderRepository.ASIGNADA);
        repository.start(orderId, Instant.now());
        return WorkOrderResponse.from(find(orderId));
    }

    /**
     * Completes an order in progress and frees the vehicle.
     *
     * @param orderId identifier of the order
     * @return the order after the transition
     */
    @Transactional
    public WorkOrderResponse complete(UUID orderId) {
        WorkOrderRepository.WorkOrderRow order = find(orderId);
        requireStatus(order, WorkOrderRepository.EN_CURSO);
        repository.complete(orderId, Instant.now());
        if (order.vehicleId() != null) {
            vehicleRepository.updateStatus(order.vehicleId(), FleetStatus.LIBRE);
        }
        return WorkOrderResponse.from(find(orderId));
    }

    /**
     * Cancels an open order and frees the vehicle.
     *
     * @param orderId identifier of the order
     * @return the order after the transition
     */
    @Transactional
    public WorkOrderResponse cancel(UUID orderId) {
        WorkOrderRepository.WorkOrderRow order = find(orderId);
        if (WorkOrderRepository.COMPLETADA.equals(order.status())
                || WorkOrderRepository.CANCELADA.equals(order.status())) {
            throw conflict(order.status(), WorkOrderRepository.CANCELADA);
        }
        repository.cancel(orderId);
        if (order.vehicleId() != null) {
            vehicleRepository.updateStatus(order.vehicleId(), FleetStatus.LIBRE);
        }
        return WorkOrderResponse.from(find(orderId));
    }

    /**
     * @param yardId optional yard
     * @return geofences of the yard
     */
    public List<GeofenceResponse> geofences(UUID yardId) {
        UUID yard = queryService.resolveYard(yardId);
        return geofenceCache.forYard(yard).stream()
                .map(zone -> new GeofenceResponse(zone.id(), zone.yardId(), zone.name(),
                        zone.kind().name(), List.of(zone.polygon().points())))
                .toList();
    }

    private WorkOrderRepository.WorkOrderRow find(UUID orderId) {
        return repository.findById(orderId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "work order not found"));
    }

    private void requireStatus(WorkOrderRepository.WorkOrderRow order, String expected) {
        if (!expected.equals(order.status())) {
            throw conflict(order.status(), expected);
        }
    }

    private ResponseStatusException conflict(String actual, String expected) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "invalid transition: expected " + expected + " but the order is " + actual);
    }

    private UUID requireFreeVehicle(WorkOrderRepository.WorkOrderRow order, UUID vehicleId) {
        boolean free = repository.findFreeVehicles(order.yardId()).stream()
                .anyMatch(candidate -> candidate.id().equals(vehicleId));
        if (!free) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "the vehicle is not free or does not belong to the yard");
        }
        return vehicleId;
    }

    private UUID nearestFreeVehicle(WorkOrderRepository.WorkOrderRow order) {
        List<WorkOrderRepository.FreeVehicle> candidates = repository.findFreeVehicles(order.yardId());
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no free vehicles");
        }
        double[] target = target(order);
        return candidates.stream()
                .min(Comparator.comparingDouble(candidate -> TrackGeometry.distance(candidate.x(),
                        candidate.y(), target[0], target[1])))
                .map(candidate -> candidate.id())
                .orElseThrow();
    }

    private double[] target(WorkOrderRepository.WorkOrderRow order) {
        return origin(order).or(() -> destination(order)).orElse(new double[] { GATE_X, GATE_Y });
    }

    private Optional<double[]> origin(WorkOrderRepository.WorkOrderRow order) {
        if (order.fromSlotId() == null) {
            return Optional.empty();
        }
        return repository.findSlotPosition(order.fromSlotId())
                .map(slot -> {
                    double x = YardLayout.xOf(slot.blockCode(), slot.bay(), slot.row());
                    double y = YardLayout.yOf(slot.row());
                    return new double[] { x, y };
                });
    }

    private Optional<double[]> destination(WorkOrderRepository.WorkOrderRow order) {
        if (order.toSlotId() == null) {
            return Optional.empty();
        }
        return repository.findSlotPosition(order.toSlotId())
                .map(slot -> {
                    double x = YardLayout.xOf(slot.blockCode(), slot.bay(), slot.row());
                    double y = YardLayout.yOf(slot.row());
                    return new double[] { x, y };
                });
    }

    /**
     * @param yardId yard
     * @return active geofences of the yard
     */
    public List<Geofence> rawGeofences(UUID yardId) {
        return geofenceCache.forYard(yardId);
    }

    /**
     * @param vehicleId identifier of the vehicle
     * @return status of the vehicle in the cache, when known
     */
    public Optional<FleetStatus> cachedStatus(UUID vehicleId) {
        return stateCache.get(vehicleId).map(state -> state.status());
    }
}
