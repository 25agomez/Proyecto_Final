package cr.logisticaportuaria.gate.api;

import cr.logisticaportuaria.gate.application.GateEventIngestionService;
import cr.logisticaportuaria.gate.application.dto.GateEventRequest;
import cr.logisticaportuaria.gate.application.dto.GateEventResponse;
import cr.logisticaportuaria.gate.application.dto.GateEventSummary;
import cr.logisticaportuaria.gate.domain.EventSource;
import cr.logisticaportuaria.gate.domain.ReadingKind;
import cr.logisticaportuaria.gate.infrastructure.mapper.GateEventMapper;
import cr.logisticaportuaria.gate.infrastructure.persistence.GateEventRepository;
import cr.logisticaportuaria.gate.infrastructure.persistence.VisitEventLink;
import cr.logisticaportuaria.gate.infrastructure.persistence.VisitLogEventRepository;
import cr.logisticaportuaria.shared.api.PageResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API of the readings of the gate.
 *
 * <p>{@code POST /api/v1/gate-events} is the ingestion point of the edge
 * service: it is idempotent by {@code eventId} (a retry answers 200 instead of
 * 201).
 */
@RestController
@RequestMapping("/api/v1/gate-events")
public class GateEventController {

    private final GateEventIngestionService ingestionService;
    private final GateEventRepository gateEventRepository;
    private final VisitLogEventRepository visitLogEventRepository;
    private final GateEventMapper mapper;

    /**
     * Constructor.
     *
     * @param ingestionService       service that stores and reconciles the readings
     * @param gateEventRepository     repository of the readings
     * @param visitLogEventRepository repository of the timeline (link with the visit)
     * @param mapper                  mapper of the DTOs
     */
    public GateEventController(GateEventIngestionService ingestionService,
            GateEventRepository gateEventRepository,
            VisitLogEventRepository visitLogEventRepository,
            GateEventMapper mapper) {
        this.ingestionService = ingestionService;
        this.gateEventRepository = gateEventRepository;
        this.visitLogEventRepository = visitLogEventRepository;
        this.mapper = mapper;
    }

    /**
     * Ingests a reading of the edge.
     *
     * @param request body of the reading
     * @return 201 when the reading is new, 200 when it had already been ingested
     */
    @PostMapping
    public ResponseEntity<GateEventResponse> ingest(@Valid @RequestBody GateEventRequest request) {
        GateEventResponse response = ingestionService.ingest(request, EventSource.API);
        HttpStatus status = response.idempotent() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Lists the readings with the given filters.
     *
     * @param gateId gate of the readings (optional)
     * @param kind   kind of the reading (optional)
     * @param from   lower bound of the period (optional)
     * @param to     upper bound of the period (optional)
     * @param text   part of the normalized text (optional)
     * @param page   zero based page number
     * @param size   size of the page
     * @return the page of readings
     */
    @GetMapping
    public PageResponse<GateEventSummary> list(
            @RequestParam(required = false) UUID gateId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String text,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        ReadingKind readingKind = kind == null || kind.isBlank() ? null : parseKind(kind);
        Page<GateEventSummary> result = gateEventRepository
                .search(gateId, readingKind, from, to, text, PageRequest.of(Math.max(0, page), clamp(size)))
                .map(this::toSummary);
        return PageResponse.of(result);
    }

    private GateEventSummary toSummary(cr.logisticaportuaria.gate.domain.GateEvent event) {
        VisitEventLink link = visitLogEventRepository.findLinkByEventId(event.getEventId().toString()).orElse(null);
        return mapper.toSummary(
                event,
                link == null ? null : link.getResult(),
                link == null ? null : link.getVisitId(),
                link == null ? null : link.getReason());
    }

    private ReadingKind parseKind(String kind) {
        try {
            return ReadingKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new cr.logisticaportuaria.shared.api.BusinessRuleException(
                    "El tipo de lectura '%s' no es válido (PLATE o CONTAINER).".formatted(kind));
        }
    }

    private int clamp(int size) {
        return Math.min(Math.max(size, 1), 200);
    }
}
