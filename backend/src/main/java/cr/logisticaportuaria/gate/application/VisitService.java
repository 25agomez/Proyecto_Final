package cr.logisticaportuaria.gate.application;

import cr.logisticaportuaria.gate.application.dto.TransitionRequest;
import cr.logisticaportuaria.gate.application.dto.VisitResponse;
import cr.logisticaportuaria.gate.domain.VisitLog;
import cr.logisticaportuaria.gate.domain.VisitLogEvent;
import cr.logisticaportuaria.gate.domain.VisitState;
import cr.logisticaportuaria.gate.infrastructure.mapper.GateMapper;
import cr.logisticaportuaria.gate.infrastructure.persistence.VisitLogEventRepository;
import cr.logisticaportuaria.gate.infrastructure.persistence.VisitLogRepository;
import cr.logisticaportuaria.shared.api.NotFoundException;
import cr.logisticaportuaria.shared.api.PageResponse;
import cr.logisticaportuaria.shared.audit.AuditService;
import cr.logisticaportuaria.shared.metrics.GateMetrics;
import cr.logisticaportuaria.shared.security.CurrentActor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Digital logbook: consultation of the visits and advance of their state.
 *
 * <p>The transitions are validated by the state machine: an invalid one fails
 * with a conflict (HTTP 409).
 */
@Service
public class VisitService {

    private final VisitLogRepository visitLogRepository;
    private final VisitLogEventRepository visitLogEventRepository;
    private final GateMapper mapper;
    private final AuditService auditService;
    private final GateMetrics metrics;

    /**
     * Constructor.
     *
     * @param visitLogRepository      repository of the visits
     * @param visitLogEventRepository repository of the timeline
     * @param mapper                  mapper of the DTOs
     * @param auditService            audit trail
     * @param metrics                 metrics of the module
     */
    public VisitService(VisitLogRepository visitLogRepository,
            VisitLogEventRepository visitLogEventRepository,
            GateMapper mapper,
            AuditService auditService,
            GateMetrics metrics) {
        this.visitLogRepository = visitLogRepository;
        this.visitLogEventRepository = visitLogEventRepository;
        this.mapper = mapper;
        this.auditService = auditService;
        this.metrics = metrics;
    }

    /**
     * Lists the visits with the given filters.
     *
     * @param yardId        yard of the visits (may be null)
     * @param state         state of the visits (may be null)
     * @param result        result of the reconciliation (may be null)
     * @param plate         plate of the truck (may be null)
     * @param containerCode container of the visit (may be null)
     * @param from          lower bound of the period (may be null)
     * @param to            upper bound of the period (may be null)
     * @param pageable      pagination
     * @return the page of visits
     */
    @Transactional(readOnly = true)
    public PageResponse<VisitResponse> list(UUID yardId,
            VisitState state,
            cr.logisticaportuaria.gate.domain.VisitResult result,
            String plate,
            String containerCode,
            Instant from,
            Instant to,
            Pageable pageable) {
        Page<VisitLog> page = visitLogRepository.search(yardId, state, result, plate, containerCode, from, to, pageable);
        return PageResponse.of(page.map(visit -> toResponse(visit, false)));
    }

    /**
     * Returns a visit with its timeline.
     *
     * @param id identifier of the visit
     * @return the visit
     */
    @Transactional(readOnly = true)
    public VisitResponse get(UUID id) {
        VisitLog visit = require(id);
        return toResponse(visit, true);
    }

    /**
     * Advances the state of a visit.
     *
     * @param id      identifier of the visit
     * @param request target state and details
     * @return the updated visit
     * @throws cr.logisticaportuaria.shared.api.ConflictException when the transition is not allowed
     */
    @Transactional
    public VisitResponse transition(UUID id, TransitionRequest request) {
        VisitLog visit = require(id);
        VisitState previous = visit.getState();
        visit.transitionTo(request.targetState(), Instant.now());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("estadoAnterior", previous.name());
        if (request.slotId() != null) {
            detail.put("slotId", request.slotId().toString());
        }
        if (request.note() != null && !request.note().isBlank()) {
            detail.put("nota", request.note());
        }
        visitLogEventRepository.save(new VisitLogEvent(
                visit.getId(),
                visit.getState().name(),
                Instant.now(),
                CurrentActor.name(),
                detail));

        if (visit.getState() == VisitState.SALIDA_PUERTA) {
            metrics.recordVisitClosed(visit.getWaitSeconds(), visit.getYardSeconds());
        }

        auditService.record(CurrentActor.name(),
                "TRANSICION_VISITA",
                "visit_log",
                visit.getId(),
                visit.getYardId(),
                Map.of(
                        "estadoAnterior", previous.name(),
                        "estadoNuevo", visit.getState().name()));

        return toResponse(visit, true);
    }

    /**
     * @param id identifier of the visit
     * @return the visit
     */
    private VisitLog require(UUID id) {
        return visitLogRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("visita", id));
    }

    private VisitResponse toResponse(VisitLog visit, boolean withTimeline) {
        List<VisitResponse.TimelineEntry> timeline = withTimeline
                ? visitLogEventRepository.findByVisitIdOrderByAtAsc(visit.getId()).stream()
                        .map(mapper::toTimelineEntry)
                        .toList()
                : List.of();
        return mapper.toVisitResponse(visit, timeline);
    }
}
