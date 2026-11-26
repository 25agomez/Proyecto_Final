package cr.logisticaportuaria.fleet.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.logisticaportuaria.fleet.domain.AlertRules;
import cr.logisticaportuaria.fleet.domain.FleetAlert;
import cr.logisticaportuaria.fleet.domain.FleetTelemetry;
import cr.logisticaportuaria.fleet.domain.Geofence;
import cr.logisticaportuaria.fleet.domain.TrackGeometry;
import cr.logisticaportuaria.fleet.domain.VehicleInfo;
import cr.logisticaportuaria.fleet.domain.VehicleState;
import cr.logisticaportuaria.fleet.infrastructure.persistence.VehiclePositionRepository;
import cr.logisticaportuaria.fleet.infrastructure.websocket.FleetBroadcaster;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ingestion of the fleet telemetry.
 *
 * <p>Flow of every message: schema and range validation, deduplication and ordering by
 * {@code ts}, rejection of physically impossible jumps, persistence in
 * {@code vehicle_position} + {@code vehicle_last_state}, update of the in-memory cache,
 * evaluation of the alert rules and queueing of the throttled WebSocket broadcast.
 *
 * <p>The processing is at-least-once: MQTT QoS 1 may deliver duplicates, which are detected
 * by the {@code ts} of the last state, so the position series never contains the same instant
 * twice (ADR-014).
 */
@Service
public class FleetTelemetryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FleetTelemetryService.class);

    private static final Pattern TOPIC = Pattern.compile(
            "^yard/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
                    + "/fleet/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
                    + "/telemetry$");

    /** Tolerance of the jump check, in meters (GPS noise of a parked vehicle). */
    private static final double JUMP_TOLERANCE_METERS = 5.0;

    private final ObjectMapper objectMapper;
    private final FleetProperties properties;
    private final VehicleDirectory directory;
    private final FleetStateCache stateCache;
    private final GeofenceCache geofenceCache;
    private final AlertStateStore alertStateStore;
    private final VehiclePositionRepository positionRepository;
    private final FleetAlertBuffer alertBuffer;
    private final FleetMetrics metrics;
    private final FleetBroadcaster broadcaster;

    /**
     * Constructor.
     *
     * @param objectMapper       mapper of the payload
     * @param properties         configuration of the module
     * @param directory          master data of the vehicles
     * @param stateCache         cache of the last states
     * @param geofenceCache      cache of the geofences
     * @param alertStateStore    context of the alert rules
     * @param positionRepository repository of positions
     * @param alertBuffer        recent alerts
     * @param metrics            counters of the module
     * @param broadcaster        WebSocket broadcaster
     */
    public FleetTelemetryService(ObjectMapper objectMapper, FleetProperties properties,
            VehicleDirectory directory, FleetStateCache stateCache, GeofenceCache geofenceCache,
            AlertStateStore alertStateStore, VehiclePositionRepository positionRepository,
            FleetAlertBuffer alertBuffer, FleetMetrics metrics, FleetBroadcaster broadcaster) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.directory = directory;
        this.stateCache = stateCache;
        this.geofenceCache = geofenceCache;
        this.alertStateStore = alertStateStore;
        this.positionRepository = positionRepository;
        this.alertBuffer = alertBuffer;
        this.metrics = metrics;
        this.broadcaster = broadcaster;
    }

    /**
     * Ingests one MQTT message.
     *
     * @param topic   topic of the broker
     * @param payload JSON payload
     * @return result of the ingestion
     */
    public IngestResult ingest(String topic, String payload) {
        long started = System.nanoTime();
        metrics.received();
        Matcher matcher = TOPIC.matcher(topic == null ? "" : topic);
        if (!matcher.matches()) {
            metrics.invalid();
            return IngestResult.INVALID;
        }
        UUID topicYard = UUID.fromString(matcher.group(1));
        UUID topicVehicle = UUID.fromString(matcher.group(2));
        FleetTelemetry telemetry;
        try {
            telemetry = objectMapper.readValue(payload, FleetTelemetry.class);
            if (telemetry.vehicleId() == null) {
                telemetry = new FleetTelemetry(topicVehicle, telemetry.ts(), telemetry.lat(),
                        telemetry.lon(), telemetry.speedKmh(), telemetry.headingDeg(),
                        telemetry.batteryPct(), telemetry.fuelPct(), telemetry.status(),
                        telemetry.workOrderId(), telemetry.containerCode());
            }
            telemetry = telemetry.normalized();
        } catch (Exception exception) {
            LOGGER.debug("Telemetria invalida en {}: {}", topic, exception.getMessage());
            metrics.invalid();
            return IngestResult.INVALID;
        }
        if (!topicVehicle.equals(telemetry.vehicleId())) {
            metrics.invalid();
            return IngestResult.INVALID;
        }
        return ingest(telemetry, topicYard, started);
    }

    /**
     * Ingests one already parsed telemetry.
     *
     * @param telemetry telemetry
     * @param yardId    yard of the topic
     * @param started   {@code System.nanoTime()} of the reception
     * @return result of the ingestion
     */
    public IngestResult ingest(FleetTelemetry telemetry, UUID yardId, long started) {
        Optional<VehicleInfo> info = directory.byId(telemetry.vehicleId());
        if (info.isEmpty()) {
            metrics.unknownVehicle();
            return IngestResult.UNKNOWN_VEHICLE;
        }
        if (!info.get().yardId().equals(yardId)) {
            metrics.invalid();
            return IngestResult.INVALID;
        }
        VehicleState previous = stateCache.get(telemetry.vehicleId())
                .or(() -> positionRepository.findLastState(telemetry.vehicleId()))
                .orElse(null);
        if (previous != null && previous.at() != null) {
            if (telemetry.ts().isBefore(previous.at())) {
                metrics.outOfOrder();
                return IngestResult.OUT_OF_ORDER;
            }
            if (telemetry.ts().equals(previous.at())) {
                metrics.duplicate();
                return IngestResult.DUPLICATE;
            }
            double distance = TrackGeometry.distance(previous.x(), previous.y(),
                    telemetry.x(), telemetry.y());
            double seconds = Duration.between(previous.at(), telemetry.ts()).toMillis() / 1000.0;
            double allowed = properties.getMaxSpeedKmh() / 3.6 * seconds + JUMP_TOLERANCE_METERS;
            if (seconds > 0 && distance > allowed) {
                double implied = distance / seconds * 3.6;
                metrics.impossibleJump();
                emit(AlertRules.impossibleJump(previous, telemetry, implied));
                return IngestResult.IMPOSSIBLE_JUMP;
            }
        }
        VehicleState state = previous == null
                ? new VehicleState(info.get().id(), info.get().yardId(), info.get().code(),
                        info.get().vehicleType(), telemetry.status(), telemetry.ts(),
                        telemetry.x(), telemetry.y(), telemetry.speedKmh(), telemetry.batteryPct(),
                        telemetry.fuelPct(), telemetry.workOrderId(), telemetry.containerCode())
                : previous.withTelemetry(telemetry);
        positionRepository.ensurePartitionFor(telemetry.ts());
        positionRepository.insert(telemetry);
        positionRepository.upsertLastState(telemetry);
        stateCache.put(state);
        metrics.persisted();
        evaluateAlerts(state, telemetry);
        broadcaster.queue(state, started);
        return IngestResult.ACCEPTED;
    }

    private void evaluateAlerts(VehicleState state, FleetTelemetry telemetry) {
        List<Geofence> zones = geofenceCache.forYard(state.yardId());
        AlertStateStore.AlertContext context = alertStateStore.context(state.vehicleId());
        synchronized (context) {
            List<FleetAlert> raised = new ArrayList<>();
            Set<UUID> zonesNow = AlertRules.zonesContaining(zones, telemetry.x(), telemetry.y());
            if (context.initialized()) {
                raised.addAll(AlertRules.zoneTransitions(zones, context.zones(), zonesNow, state,
                        telemetry.ts()));
            }
            context.zones(zonesNow);
            context.silent(false);

            speak(AlertRules.speed(state, telemetry, properties.getSpeedLimitKmh()), context,
                    telemetry.ts(), raised);
            speak(AlertRules.lowBattery(state, telemetry, properties.getBatteryLowPct()), context,
                    telemetry.ts(), raised);
            speak(AlertRules.breakdown(state, telemetry), context, telemetry.ts(), raised);
            speak(AlertRules.forbiddenZone(state, telemetry, zones), context, telemetry.ts(),
                    raised);

            if (telemetry.speedKmh() <= properties.getIdleSpeedKmh()) {
                if (context.stoppedSince() == null) {
                    context.stoppedSince(telemetry.ts());
                } else {
                    speak(AlertRules.prolongedIdle(state, telemetry, properties.getIdleMinutes(),
                            properties.getIdleSpeedKmh(), context.stoppedSince()), context,
                            telemetry.ts(), raised);
                }
            } else {
                context.stoppedSince(null);
            }
            raised.forEach(this::emit);
        }
    }

    private void speak(Optional<FleetAlert> alert,
            AlertStateStore.AlertContext context, Instant now, List<FleetAlert> target) {
        alert.filter(value -> value.severity() == cr.logisticaportuaria.fleet.domain.AlertType.Severity.INFO
                        || context.canRaise(value.type(), now, properties.getAlertCooldownMinutes()))
                .ifPresent(target::add);
    }

    private void emit(FleetAlert alert) {
        alertBuffer.add(alert);
        metrics.alert();
        broadcaster.broadcastAlert(alert);
    }

    /**
     * Raises the {@code SIN_SENAL} alerts of the vehicles that stopped sending telemetry.
     *
     * @param now instant of the check
     * @return number of alerts raised
     */
    public int checkSilence(Instant now) {
        int raised = 0;
        for (VehicleState state : stateCache.all()) {
            AlertStateStore.AlertContext context = alertStateStore.context(state.vehicleId());
            synchronized (context) {
                Optional<FleetAlert> alert = AlertRules.silence(state, now,
                        properties.getSilenceSeconds());
                if (alert.isEmpty()) {
                    context.silent(false);
                    continue;
                }
                if (!context.silent()) {
                    context.silent(true);
                    if (context.canRaise(alert.get().type(), now,
                            properties.getAlertCooldownMinutes())) {
                        emit(alert.get());
                        raised++;
                    }
                }
            }
        }
        return raised;
    }

    /**
     * Applies the downsampling and the retention.
     *
     * @param now instant of reference
     * @return report of the job
     */
    public RetentionReport applyRetention(Instant now) {
        Instant retentionLimit = now.minus(Duration.ofDays(properties.getRetentionDays()));
        int purged = positionRepository.deleteOlderThan(retentionLimit);
        Instant downsampleEnd = now.minus(Duration.ofHours(properties.getDownsampleAfterHours()));
        int downsampled = 0;
        if (downsampleEnd.isAfter(retentionLimit)) {
            downsampled = positionRepository.downsample(retentionLimit, downsampleEnd,
                    properties.getDownsampleSeconds());
        }
        return new RetentionReport(purged, downsampled);
    }

    /**
     * Result of the retention job.
     *
     * @param purged      points deleted because they are older than the retention
     * @param downsampled points deleted by the downsampling
     */
    public record RetentionReport(int purged, int downsampled) {
    }
}
