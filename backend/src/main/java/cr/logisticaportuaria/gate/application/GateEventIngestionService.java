package cr.logisticaportuaria.gate.application;

import cr.logisticaportuaria.gate.application.dto.GateEventRequest;
import cr.logisticaportuaria.gate.application.dto.GateEventResponse;
import cr.logisticaportuaria.gate.domain.EventSource;
import cr.logisticaportuaria.gate.domain.GateEvent;
import cr.logisticaportuaria.gate.domain.ReadingKind;
import cr.logisticaportuaria.gate.domain.ReconciliationDecision;
import cr.logisticaportuaria.gate.domain.ReviewStatus;
import cr.logisticaportuaria.gate.domain.ReviewTask;
import cr.logisticaportuaria.gate.domain.VisitLog;
import cr.logisticaportuaria.gate.domain.VisitLogEvent;
import cr.logisticaportuaria.gate.domain.VisitResult;
import cr.logisticaportuaria.gate.domain.VisitState;
import cr.logisticaportuaria.gate.infrastructure.persistence.GateEventRepository;
import cr.logisticaportuaria.gate.infrastructure.persistence.ReviewTaskRepository;
import cr.logisticaportuaria.gate.infrastructure.persistence.VisitLogEventRepository;
import cr.logisticaportuaria.gate.infrastructure.persistence.VisitLogRepository;
import cr.logisticaportuaria.shared.api.BusinessRuleException;
import cr.logisticaportuaria.shared.audit.AuditService;
import cr.logisticaportuaria.shared.domain.Direction;
import cr.logisticaportuaria.shared.domain.TextNormalizer;
import cr.logisticaportuaria.shared.metrics.GateMetrics;
import cr.logisticaportuaria.shared.security.CurrentActor;
import cr.logisticaportuaria.yard.domain.Gate;
import cr.logisticaportuaria.yard.infrastructure.persistence.GateRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingestion of the readings of the edge: stores the event, reconciles it and
 * updates the digital logbook and the manual review queue.
 *
 * <p>The ingestion is idempotent by {@code eventId}: the same reading received
 * twice (a retry of the edge) is stored once and answered with the result of
 * the first time.
 */
@Service
public class GateEventIngestionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GateEventIngestionService.class);

    private static final List<VisitState> OPEN_STATES = List.of(VisitState.ENTRADA_PUERTA, VisitState.VALIDADO);

    private final GateEventRepository gateEventRepository;
    private final VisitLogRepository visitLogRepository;
    private final VisitLogEventRepository visitLogEventRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final GateRepository gateRepository;
    private final GateReconciliationService reconciliationService;
    private final AuditService auditService;
    private final GateMetrics metrics;
    private final GateProperties properties;

    /**
     * Constructor.
     *
     * @param gateEventRepository     repository of the readings
     * @param visitLogRepository      repository of the digital logbook
     * @param visitLogEventRepository repository of the timeline
     * @param reviewTaskRepository    repository of the manual review queue
     * @param gateRepository          repository of the gates
     * @param reconciliationService   rules of the reconciliation
     * @param auditService            audit trail
     * @param metrics                 metrics of the module
     * @param properties              configuration of the module
     */
    public GateEventIngestionService(GateEventRepository gateEventRepository,
            VisitLogRepository visitLogRepository,
            VisitLogEventRepository visitLogEventRepository,
            ReviewTaskRepository reviewTaskRepository,
            GateRepository gateRepository,
            GateReconciliationService reconciliationService,
            AuditService auditService,
            GateMetrics metrics,
            GateProperties properties) {
        this.gateEventRepository = gateEventRepository;
        this.visitLogRepository = visitLogRepository;
        this.visitLogEventRepository = visitLogEventRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.gateRepository = gateRepository;
        this.reconciliationService = reconciliationService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.properties = properties;
    }

    /**
     * Ingests a reading.
     *
     * @param request body received from the edge
     * @param source  origin of the event
     * @return the result of the ingestion
     */
    @Transactional
    public GateEventResponse ingest(GateEventRequest request, EventSource source) {
        Optional<GateEvent> existing = gateEventRepository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            GateEvent stored = existing.get();
            LOGGER.info("Lectura {} ya ingerida: se responde de forma idempotente", request.eventId());
            return responseOf(stored, true);
        }

        Gate gate = gateRepository.findById(request.gateId())
                .orElseThrow(() -> new BusinessRuleException(
                        "La puerta %s no existe en el sistema.".formatted(request.gateId())));
        UUID yardId = request.yardId() == null ? gate.getYardId() : request.yardId();

        ReadingKind kind = parseKind(request.kind());
        String normalized = normalize(kind, request.normalizedText(), request.rawText());

        GateEvent event = new GateEvent(
                request.eventId(),
                yardId,
                request.gateId(),
                request.timestamp(),
                kind,
                request.rawText(),
                normalized,
                request.confidence() == null ? null : BigDecimal.valueOf(request.confidence()),
                request.isoValid(),
                request.corrected(),
                request.bbox(),
                request.evidenceImagePath(),
                request.modelVersion(),
                request.latencyMs(),
                source);
        gateEventRepository.save(event);

        ReconciliationDecision decision = reconciliationService.reconcile(event);
        VisitLog visit = resolveVisit(event, decision);
        applyDecision(visit, event, decision);

        UUID reviewTaskId = createReviewTaskIfNeeded(event, visit, decision);

        metrics.recordEvent(kind.name(), decision.result().name());
        auditService.record(CurrentActor.name(),
                "INGESTA_LECTURA",
                "gate_event",
                event.getId(),
                yardId,
                Map.of(
                        "eventId", event.getEventId().toString(),
                        "kind", kind.name(),
                        "result", decision.result().name(),
                        "reason", decision.reason(),
                        "visitId", visit.getId().toString()));

        return new GateEventResponse(
                event.getId(),
                event.getEventId(),
                decision.result().name(),
                visit.getId(),
                reviewTaskId,
                decision.reason(),
                false);
    }

    /**
     * Finds the visit that the reading belongs to, or opens a new one.
     *
     * @param event    the reading
     * @param decision result of the reconciliation
     * @return the visit
     */
    private VisitLog resolveVisit(GateEvent event, ReconciliationDecision decision) {
        UUID yardId = event.getYardId();
        VisitLog visit = null;

        if (decision.truckId() != null) {
            visit = visitLogRepository
                    .findByYardIdAndTruckIdAndStateInOrderByEnteredAtDesc(yardId, decision.truckId(), OPEN_STATES)
                    .stream()
                    .filter(candidate -> candidate.getGateId().equals(event.getGateId()))
                    .findFirst()
                    .orElse(null);
        }

        if (visit == null && event.getKind() == ReadingKind.CONTAINER) {
            Instant limit = event.getObservedAt().minus(Duration.ofMinutes(properties.getVisitMatchWindowMinutes()));
            visit = visitLogRepository.findByGateIdAndStateInOrderByEnteredAtDesc(event.getGateId(), OPEN_STATES)
                    .stream()
                    .filter(candidate -> candidate.getEnteredAt() != null && !candidate.getEnteredAt().isBefore(limit))
                    .filter(candidate -> candidate.getContainerCode() == null || candidate.getContainerCode().isBlank())
                    .findFirst()
                    .orElse(null);
        }

        if (visit == null) {
            Direction direction = decision.direction() == null ? Direction.IN : decision.direction();
            visit = new VisitLog(yardId, event.getGateId(), direction, event.getObservedAt());
            visitLogRepository.save(visit);
            addTimeline(visit, VisitState.ENTRADA_PUERTA, Map.of(
                    "eventId", event.getEventId().toString(),
                    "kind", event.getKind().name()));
        }
        return visit;
    }

    /**
     * Writes the result of the reconciliation in the visit.
     *
     * @param visit    the visit
     * @param event    the reading
     * @param decision result of the reconciliation
     */
    private void applyDecision(VisitLog visit, GateEvent event, ReconciliationDecision decision) {
        visit.setResult(decision.result());
        visit.setReason(decision.reason());
        if (decision.direction() != null) {
            visit.setDirection(decision.direction());
        }
        if (decision.truckId() != null) {
            visit.setTruckId(decision.truckId());
        }
        if (decision.appointmentId() != null) {
            visit.setAppointmentId(decision.appointmentId());
        }
        if (visit.getPlate() == null && event.getKind() == ReadingKind.PLATE) {
            visit.setPlate(event.getNormalizedText());
        }
        if (decision.plate() != null && visit.getPlate() == null) {
            visit.setPlate(decision.plate());
        }
        if (event.getKind() == ReadingKind.CONTAINER) {
            visit.setContainerCode(decision.containerCode());
            if (decision.containerId() != null) {
                visit.setContainerId(decision.containerId());
            }
        }
        if (decision.result() == VisitResult.AUTO_APROBADO && visit.getState() == VisitState.ENTRADA_PUERTA) {
            visit.transitionTo(VisitState.VALIDADO, Instant.now());
            addTimeline(visit, VisitState.VALIDADO, Map.of(
                    "eventId", event.getEventId().toString(),
                    "reason", decision.reason()));
            return;
        }
        addTimeline(visit, visit.getState(), Map.of(
                "eventId", event.getEventId().toString(),
                "result", decision.result().name(),
                "reason", decision.reason()));
    }

    /**
     * Creates the task of the manual review queue when the reading was not
     * accepted automatically.
     *
     * @param event    the reading
     * @param visit    the visit
     * @param decision result of the reconciliation
     * @return the identifier of the task (null when there is nothing to review)
     */
    private UUID createReviewTaskIfNeeded(GateEvent event, VisitLog visit, ReconciliationDecision decision) {
        if (!decision.requiresReview()) {
            return null;
        }
        ReviewTask task = new ReviewTask(
                event.getId(),
                visit.getId(),
                event.getYardId(),
                event.getKind(),
                event.getRawText(),
                event.getNormalizedText(),
                decision.reason());
        reviewTaskRepository.save(task);
        metrics.recordReviewTaskCreated();
        return task.getId();
    }

    private void addTimeline(VisitLog visit, VisitState stage, Map<String, Object> detail) {
        visitLogEventRepository.save(new VisitLogEvent(
                visit.getId(),
                stage.name(),
                Instant.now(),
                CurrentActor.name(),
                new LinkedHashMap<>(detail)));
    }

    private GateEventResponse responseOf(GateEvent event, boolean idempotent) {
        ReconciliationDecision decision = reconciliationService.reconcile(event);
        UUID visitId = visitLogEventRepository.findVisitIdByEventId(event.getEventId().toString()).orElse(null);
        UUID reviewTaskId = reviewTaskRepository.findByGateEventIdAndStatus(event.getId(), ReviewStatus.PENDIENTE)
                .map(ReviewTask::getId)
                .orElse(null);
        return new GateEventResponse(
                event.getId(),
                event.getEventId(),
                decision.result().name(),
                visitId,
                reviewTaskId,
                decision.reason(),
                idempotent);
    }

    private ReadingKind parseKind(String kind) {
        try {
            return ReadingKind.valueOf(kind.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new BusinessRuleException("El tipo de lectura '%s' no es válido (PLATE o CONTAINER).".formatted(kind));
        }
    }

    private String normalize(ReadingKind kind, String normalizedText, String rawText) {
        String source = normalizedText == null || normalizedText.isBlank() ? rawText : normalizedText;
        if (source == null) {
            return null;
        }
        return kind == ReadingKind.CONTAINER
                ? TextNormalizer.normalizeContainer(source)
                : TextNormalizer.normalizePlate(source);
    }
}
