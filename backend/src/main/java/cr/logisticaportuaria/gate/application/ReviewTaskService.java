package cr.logisticaportuaria.gate.application;

import cr.logisticaportuaria.gate.application.dto.ResolveReviewRequest;
import cr.logisticaportuaria.gate.application.dto.ReviewTaskResponse;
import cr.logisticaportuaria.gate.domain.GateEvent;
import cr.logisticaportuaria.gate.domain.ReadingKind;
import cr.logisticaportuaria.gate.domain.ReviewStatus;
import cr.logisticaportuaria.gate.domain.ReviewTask;
import cr.logisticaportuaria.gate.domain.VisitLog;
import cr.logisticaportuaria.gate.domain.VisitResult;
import cr.logisticaportuaria.gate.domain.VisitState;
import cr.logisticaportuaria.gate.infrastructure.mapper.GateMapper;
import cr.logisticaportuaria.gate.infrastructure.persistence.GateEventRepository;
import cr.logisticaportuaria.gate.infrastructure.persistence.ReviewTaskRepository;
import cr.logisticaportuaria.gate.infrastructure.persistence.VisitLogEventRepository;
import cr.logisticaportuaria.gate.infrastructure.persistence.VisitLogRepository;
import cr.logisticaportuaria.shared.api.BusinessRuleException;
import cr.logisticaportuaria.shared.api.ConflictException;
import cr.logisticaportuaria.shared.api.NotFoundException;
import cr.logisticaportuaria.shared.audit.AuditService;
import cr.logisticaportuaria.shared.domain.Iso6346;
import cr.logisticaportuaria.shared.domain.TextNormalizer;
import cr.logisticaportuaria.shared.metrics.GateMetrics;
import cr.logisticaportuaria.shared.security.CurrentActor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual review queue: the readings that the reconciliation could not accept
 * automatically are approved, corrected or rejected by an operator, and every
 * resolution is registered in the audit trail.
 */
@Service
public class ReviewTaskService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskService.class);

    /** Maximum number of tasks returned by the listing. */
    private static final int MAX_PAGE_SIZE = 200;

    private final ReviewTaskRepository reviewTaskRepository;
    private final GateEventRepository gateEventRepository;
    private final VisitLogRepository visitLogRepository;
    private final VisitLogEventRepository visitLogEventRepository;
    private final GateMapper mapper;
    private final AuditService auditService;
    private final GateMetrics metrics;

    /**
     * Constructor.
     *
     * @param reviewTaskRepository    repository of the queue
     * @param gateEventRepository     repository of the readings
     * @param visitLogRepository      repository of the digital logbook
     * @param visitLogEventRepository repository of the timeline
     * @param mapper                  mapper of the DTOs
     * @param auditService            audit trail
     * @param metrics                 metrics of the module
     */
    public ReviewTaskService(ReviewTaskRepository reviewTaskRepository,
            GateEventRepository gateEventRepository,
            VisitLogRepository visitLogRepository,
            VisitLogEventRepository visitLogEventRepository,
            GateMapper mapper,
            AuditService auditService,
            GateMetrics metrics) {
        this.reviewTaskRepository = reviewTaskRepository;
        this.gateEventRepository = gateEventRepository;
        this.visitLogRepository = visitLogRepository;
        this.visitLogEventRepository = visitLogEventRepository;
        this.mapper = mapper;
        this.auditService = auditService;
        this.metrics = metrics;
    }

    /**
     * Lists the tasks of the queue.
     *
     * @param yardId yard of the tasks (may be null)
     * @param status status of the tasks (may be null)
     * @return the tasks
     */
@Transactional(readOnly = true)
    public List<ReviewTaskResponse> list(UUID yardId, ReviewStatus status) {
        return reviewTaskRepository.search(yardId, status, PageRequest.of(0, MAX_PAGE_SIZE))
                .getContent()
                .stream()
                .map(mapper::toReviewTaskResponse)
                .toList();
    }

    /**
     * Resolves a task of the queue.
     *
     * @param id      identifier of the task
     * @param request resolution of the operator
     * @return the resolved task
     * @throws ConflictException when the task was already resolved
     */
    @Transactional
    public ReviewTaskResponse resolve(UUID id, ResolveReviewRequest request) {
        ReviewTask task = reviewTaskRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("tarea de revisión", id));
        if (task.getStatus() != ReviewStatus.PENDIENTE) {
            throw new ConflictException(
                    "La tarea de revisión ya fue resuelta (%s).".formatted(task.getStatus()));
        }

        ReviewStatus resolution = parseResolution(request.resolution());
        GateEvent event = gateEventRepository.findById(task.getGateEventId())
                .orElseThrow(() -> new NotFoundException("evento de puerta", task.getGateEventId()));

        String finalText = resolveText(event, request);
        event.setNormalizedText(finalText);
        if (resolution == ReviewStatus.CORREGIDO) {
            event.setCorrected(true);
            event.setIsoValid(event.getKind() == ReadingKind.CONTAINER ? Iso6346.isValid(finalText) : null);
        }

        VisitLog visit = task.getVisitId() == null
                ? null
                : visitLogRepository.findById(task.getVisitId()).orElse(null);
        if (visit != null) {
            applyToVisit(visit, event, resolution, finalText, request.reason());
        }

        String actor = CurrentActor.name();
        task.resolve(resolution, finalText, actor, Instant.now());
        metrics.recordReviewTaskResolved();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resolution", resolution.name());
        payload.put("textoFinal", finalText);
        if (request.reason() != null) {
            payload.put("motivo", request.reason());
        }
        auditService.record(actor, "RESOLUCION_REVISION", "review_task", task.getId(), task.getYardId(), payload);

        LOGGER.info("Tarea de revisión {} resuelta como {} por {}", task.getId(), resolution, actor);
        return mapper.toReviewTaskResponse(task);
    }

    private void applyToVisit(VisitLog visit, GateEvent event, ReviewStatus resolution, String finalText,
            String reason) {
        String actor = CurrentActor.name();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("resolucion", resolution.name());
        detail.put("textoFinal", finalText);
        detail.put("actor", actor);
        if (reason != null && !reason.isBlank()) {
            detail.put("motivo", reason);
        }

        if (resolution == ReviewStatus.RECHAZADO) {
            visit.setResult(VisitResult.RECHAZADO);
            visit.setReason("Rechazada en la revisión manual por %s.".formatted(actor));
        } else {
            visit.setResult(VisitResult.AUTO_APROBADO);
            visit.setReason("Aprobada en la revisión manual por %s.".formatted(actor));
            if (event.getKind() == ReadingKind.PLATE) {
                visit.setPlate(finalText);
            } else {
                visit.setContainerCode(finalText);
            }
            if (visit.getState() == VisitState.ENTRADA_PUERTA) {
                visit.transitionTo(VisitState.VALIDADO, Instant.now());
            }
        }

        visitLogEventRepository.save(new cr.logisticaportuaria.gate.domain.VisitLogEvent(
                visit.getId(),
                visit.getState().name(),
                Instant.now(),
                actor,
                detail));
    }

    private String resolveText(GateEvent event, ResolveReviewRequest request) {
        if (request.correctedText() == null || request.correctedText().isBlank()) {
            return event.getNormalizedText();
        }
        String candidate = request.correctedText().trim();
        return event.getKind() == ReadingKind.CONTAINER
                ? TextNormalizer.normalizeContainer(candidate)
                : TextNormalizer.normalizePlate(candidate);
    }

    private ReviewStatus parseResolution(String resolution) {
        try {
            ReviewStatus status = ReviewStatus.valueOf(resolution.trim().toUpperCase(Locale.ROOT));
            if (status == ReviewStatus.PENDIENTE) {
                throw new BusinessRuleException("La resolución debe ser APROBADO, CORREGIDO o RECHAZADO.");
            }
            return status;
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleException(
                    "La resolución '%s' no es válida (APROBADO, CORREGIDO o RECHAZADO).".formatted(resolution));
        }
    }
}
