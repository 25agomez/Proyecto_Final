package cr.logisticaportuaria.fleet.application;

import cr.logisticaportuaria.fleet.application.dto.ContainerTraceResponse;
import cr.logisticaportuaria.fleet.application.dto.FleetKpisResponse;
import cr.logisticaportuaria.fleet.application.dto.TrackResponse;
import cr.logisticaportuaria.fleet.application.dto.VehicleResponse;
import cr.logisticaportuaria.fleet.domain.FleetStatus;
import cr.logisticaportuaria.fleet.domain.TrackGeometry;
import cr.logisticaportuaria.fleet.domain.VehicleInfo;
import cr.logisticaportuaria.fleet.domain.VehicleState;
import cr.logisticaportuaria.fleet.infrastructure.persistence.ContainerTraceRepository;
import cr.logisticaportuaria.fleet.infrastructure.persistence.FleetVehicleRepository;
import cr.logisticaportuaria.fleet.infrastructure.persistence.VehiclePositionRepository;
import cr.logisticaportuaria.fleet.infrastructure.persistence.WorkOrderRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read queries of the fleet dashboard: vehicles, track, KPIs and container trace.
 */
@Service
public class FleetQueryService {

    /** Maximum duration of a segment attributed to a status or to the distance. */
    private static final long MAX_SEGMENT_SECONDS = 300;

    /** Maximum duration of a segment used to add distance (avoids offline gaps). */
    private static final long MAX_DISTANCE_SEGMENT_SECONDS = 60;

    private final FleetProperties properties;
    private final VehicleDirectory directory;
    private final FleetStateCache stateCache;
    private final FleetVehicleRepository vehicleRepository;
    private final VehiclePositionRepository positionRepository;
    private final WorkOrderRepository workOrderRepository;
    private final ContainerTraceRepository traceRepository;
    private final FleetAlertBuffer alertBuffer;
    private final FleetMetrics metrics;

    /**
     * Constructor.
     *
     * @param properties          configuration of the module
     * @param directory           master data of the vehicles
     * @param stateCache          cache of the last states
     * @param vehicleRepository   access to the vehicles
     * @param positionRepository  access to the positions
     * @param workOrderRepository access to the work orders
     * @param traceRepository     access to the trace of a container
     * @param alertBuffer         recent alerts
     * @param metrics             counters of the module
     */
    public FleetQueryService(FleetProperties properties, VehicleDirectory directory,
            FleetStateCache stateCache, FleetVehicleRepository vehicleRepository,
            VehiclePositionRepository positionRepository, WorkOrderRepository workOrderRepository,
            ContainerTraceRepository traceRepository, FleetAlertBuffer alertBuffer,
            FleetMetrics metrics) {
        this.properties = properties;
        this.directory = directory;
        this.stateCache = stateCache;
        this.vehicleRepository = vehicleRepository;
        this.positionRepository = positionRepository;
        this.workOrderRepository = workOrderRepository;
        this.traceRepository = traceRepository;
        this.alertBuffer = alertBuffer;
        this.metrics = metrics;
    }

    /**
     * Resolves the yard of a query.
     *
     * @param requested optional yard of the request
     * @return the requested yard, the configured one or the only yard of the database
     */
    public UUID resolveYard(UUID requested) {
        if (requested != null) {
            return requested;
        }
        if (properties.getYardId() != null) {
            return properties.getYardId();
        }
        return vehicleRepository.singleYard().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "yardId is required: the database has no yard"));
    }

    /**
     * @param yardId optional yard filter
     * @param status optional status filter
     * @return vehicles with their last state
     */
    public List<VehicleResponse> vehicles(UUID yardId, String status) {
        UUID yard = resolveYard(yardId);
        FleetStatus filter = status == null || status.isBlank() ? null : FleetStatus.parse(status);
        if (status != null && !status.isBlank() && filter == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: " + status);
        }
        Instant now = Instant.now();
        List<VehicleResponse> result = new ArrayList<>();
        for (VehicleInfo info : directory.vehicles()) {
            if (!info.yardId().equals(yard)) {
                continue;
            }
            VehicleState state = stateCache.get(info.id())
                    .or(() -> positionRepository.findLastState(info.id()))
                    .orElse(null);
            FleetStatus effective = state == null ? info.status() : state.status();
            if (filter != null && effective != filter) {
                continue;
            }
            if (state == null) {
                result.add(new VehicleResponse(info.id(), info.code(), info.vehicleType(),
                        effective.name(), null, true));
            } else {
                stateCache.put(state);
                result.add(VehicleResponse.from(state,
                        state.isSilent(now, properties.getSilenceSeconds())));
            }
        }
        result.sort(Comparator.comparing(VehicleResponse::code));
        return result;
    }

    /**
     * @param vehicleId identifier of the vehicle
     * @param from      optional inclusive start (default: two hours ago)
     * @param to        optional exclusive end (default: now)
     * @return GeoJSON LineString of the track
     */
    public TrackResponse track(UUID vehicleId, Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(2)) : from;
        if (!end.isAfter(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be after from");
        }
        if (directory.byId(vehicleId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "vehicle not found");
        }
        List<VehiclePositionRepository.TrackPoint> points = positionRepository.findTrack(vehicleId,
                start, end, properties.getTrackMaxPoints());
        List<double[]> coordinates = TrackGeometry.toLonLat(
                points.stream().map(VehiclePositionRepository.TrackPoint::x).toList(),
                points.stream().map(VehiclePositionRepository.TrackPoint::y).toList());
        return TrackResponse.lineString(coordinates);
    }

    /**
     * @param yardId optional yard
     * @param from   optional inclusive start (default: 24 hours ago)
     * @param to     optional exclusive end (default: now)
     * @return KPIs of the fleet
     */
    public FleetKpisResponse kpis(UUID yardId, Instant from, Instant to) {
        UUID yard = resolveYard(yardId);
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(24)) : from;
        if (!end.isAfter(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be after from");
        }
        List<VehiclePositionRepository.KpiSample> samples = positionRepository.findSamples(yard,
                start, end, properties.getKpiMaxPoints());
        Map<UUID, List<VehiclePositionRepository.KpiSample>> byVehicle = samples.stream()
                .collect(Collectors.groupingBy(VehiclePositionRepository.KpiSample::vehicleId));

        double productive = 0;
        double idle = 0;
        double maintenance = 0;
        double distanceMeters = 0;
        for (List<VehiclePositionRepository.KpiSample> vehicleSamples : byVehicle.values()) {
            for (int i = 0; i < vehicleSamples.size() - 1; i++) {
                VehiclePositionRepository.KpiSample current = vehicleSamples.get(i);
                VehiclePositionRepository.KpiSample next = vehicleSamples.get(i + 1);
                long seconds = Duration.between(current.at(), next.at()).toSeconds();
                if (seconds <= 0 || seconds > MAX_SEGMENT_SECONDS) {
                    continue;
                }
                if (current.status().isProductive()) {
                    productive += seconds;
                } else if (current.status() == FleetStatus.AVERIA) {
                    maintenance += seconds;
                } else {
                    idle += seconds;
                }
                if (seconds <= MAX_DISTANCE_SEGMENT_SECONDS) {
                    distanceMeters += TrackGeometry.distance(current.x(), current.y(), next.x(),
                            next.y());
                }
            }
        }
        double total = productive + idle + maintenance;
        double utilization = total > 0 ? productive / total * 100.0 : 0.0;
        double idlePct = total > 0 ? idle / total * 100.0 : 0.0;
        double maintenancePct = total > 0 ? maintenance / total * 100.0 : 0.0;
        Double averageCycle = workOrderRepository.averageCycleMinutes(yard, start, end);
        long completed = workOrderRepository.countCompleted(yard, start, end);
        return new FleetKpisResponse(yard, start, end, byVehicle.size(),
                round(utilization), round(idlePct), round(maintenancePct), round(distanceMeters / 1000.0),
                averageCycle == null ? null : round(averageCycle), completed,
                alertBuffer.countByType(start), metrics.snapshot());
    }

    /**
     * @param code container code
     * @return trace of the container
     */
    public ContainerTraceResponse trace(String code) {
        String normalized = code == null ? "" : code.replace(" ", "").toUpperCase(Locale.ROOT);
        UUID containerId = traceRepository.findContainerId(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "container not found: " + normalized));
        List<ContainerTraceResponse.Entry> entries = new ArrayList<>();
        for (ContainerTraceRepository.GateReading reading : traceRepository.findGateEvents(normalized)) {
            entries.add(new ContainerTraceResponse.Entry("GATE_EVENT", reading.at(),
                    String.format(Locale.ROOT, "Lectura OCR de contenedor en la puerta %s (confianza %s)",
                            reading.gateCode(), reading.confidence() == null
                                    ? "NO MEDIDO" : Math.round(reading.confidence() * 100) + " %"),
                    reading.gateCode(), null, null));
        }
        for (ContainerTraceRepository.VisitReading visit : traceRepository.findVisits(normalized)) {
            entries.add(new ContainerTraceResponse.Entry("VISIT", visit.enteredAt(),
                    String.format("Visita %s en estado %s (resultado %s)", visit.direction(),
                            visit.state(), visit.result()),
                    visit.gateCode(), null, null));
            if (visit.exitedAt() != null) {
                entries.add(new ContainerTraceResponse.Entry("VISIT", visit.exitedAt(),
                        "Salida de la puerta " + visit.gateCode(), visit.gateCode(), null, null));
            }
        }
        for (ContainerTraceRepository.PlacementReading placement
                : traceRepository.findPlacements(containerId)) {
            String location = String.format("bloque %s, bahia %d, fila %d, nivel %d",
                    placement.blockCode(), placement.bay(), placement.row(), placement.tier());
            entries.add(new ContainerTraceResponse.Entry("YARD_POSITION", placement.placedAt(),
                    "Ubicado en " + location, null, null, location));
            if (placement.removedAt() != null) {
                entries.add(new ContainerTraceResponse.Entry("YARD_POSITION", placement.removedAt(),
                        "Retirado de " + location, null, null, location));
            }
        }
        for (ContainerTraceRepository.WorkOrderReading order : traceRepository.findWorkOrders(normalized)) {
            Instant at = order.startedAt() != null ? order.startedAt() : order.createdAt();
            entries.add(new ContainerTraceResponse.Entry("WORK_ORDER", at,
                    String.format("Orden %s en estado %s", order.orderType(), order.status()),
                    null, order.vehicleCode(), null));
        }
        entries.sort(Comparator.comparing(ContainerTraceResponse.Entry::at));
        return new ContainerTraceResponse(normalized, entries);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
