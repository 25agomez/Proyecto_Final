package cr.logisticaportuaria.gate.api;

import cr.logisticaportuaria.gate.application.VisitExportService;
import cr.logisticaportuaria.gate.application.VisitService;
import cr.logisticaportuaria.gate.application.dto.TransitionRequest;
import cr.logisticaportuaria.gate.application.dto.VisitResponse;
import cr.logisticaportuaria.gate.domain.VisitLog;
import cr.logisticaportuaria.gate.domain.VisitResult;
import cr.logisticaportuaria.gate.domain.VisitState;
import cr.logisticaportuaria.gate.infrastructure.persistence.VisitLogRepository;
import cr.logisticaportuaria.shared.api.NotFoundException;
import cr.logisticaportuaria.shared.api.PageResponse;
import cr.logisticaportuaria.yard.domain.Yard;
import cr.logisticaportuaria.yard.infrastructure.persistence.YardRepository;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
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
 * API of the digital logbook (bitácora digital).
 */
@RestController
@RequestMapping("/api/v1/visits")
public class VisitController {

    private final VisitService visitService;
    private final VisitExportService exportService;
    private final VisitLogRepository visitLogRepository;
    private final YardRepository yardRepository;

    /**
     * Constructor.
     *
     * @param visitService       service of the visits
     * @param exportService      service of the exports
     * @param visitLogRepository repository of the visits
     * @param yardRepository     repository of the yards
     */
    public VisitController(VisitService visitService,
            VisitExportService exportService,
            VisitLogRepository visitLogRepository,
            YardRepository yardRepository) {
        this.visitService = visitService;
        this.exportService = exportService;
        this.visitLogRepository = visitLogRepository;
        this.yardRepository = yardRepository;
    }

    /**
     * Lists the visits of the logbook.
     *
     * @param yardId        yard of the visits (optional)
     * @param state         state of the visits (optional)
     * @param result        result of the reconciliation (optional)
     * @param plate         plate of the truck (optional)
     * @param containerCode container of the visit (optional)
     * @param from          lower bound of the period (optional)
     * @param to            upper bound of the period (optional)
     * @param page          zero based page number
     * @param size          size of the page
     * @return the page of visits
     */
    @GetMapping
    public PageResponse<VisitResponse> list(
            @RequestParam(required = false) UUID yardId,
            @RequestParam(required = false) VisitState state,
            @RequestParam(required = false) VisitResult result,
            @RequestParam(required = false) String plate,
            @RequestParam(required = false) String containerCode,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return visitService.list(
                yardId,
                state,
                result,
                plate,
                containerCode,
                from,
                to,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 200)));
    }

    /**
     * Returns a visit with its timeline.
     *
     * @param id identifier of the visit
     * @return the visit
     */
    @GetMapping("/{id}")
    public VisitResponse get(@PathVariable UUID id) {
        return visitService.get(id);
    }

    /**
     * Advances the state of a visit. An invalid transition answers 409.
     *
     * @param id      identifier of the visit
     * @param request target state
     * @return the updated visit
     */
    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAnyRole('OPERADOR_PUERTA', 'SUPERVISOR', 'ADMIN')")
    public VisitResponse transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return visitService.transition(id, request);
    }

    /**
     * Printable receipt of a visit (PDF with a QR code).
     *
     * @param id identifier of the visit
     * @return the PDF
     */
    @GetMapping({"/{id}/receipt.pdf", "/{id}/comprobante"})
    public ResponseEntity<byte[]> receipt(@PathVariable UUID id) {
        VisitLog visit = visitLogRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("visita", id));
        String yardName = yardRepository.findById(visit.getYardId())
                .map(Yard::getName)
                .orElse("Patio");
        byte[] pdf = exportService.toPdf(visit, yardName);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"visita-" + id + ".pdf\"")
                .body(pdf);
    }

    /**
     * Exports the visits of a period as a CSV.
     *
     * @param yardId yard of the visits (optional)
     * @param from   lower bound of the period (optional)
     * @param to     upper bound of the period (optional)
     * @return the CSV
     */
    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) UUID yardId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Instant start = from == null ? Instant.now().minusSeconds(7L * 24 * 3600) : from;
        Instant end = to == null ? Instant.now() : to;
        List<VisitLog> visits = yardId == null
                ? visitLogRepository.findAll().stream()
                        .filter(visit -> !visit.getEnteredAt().isBefore(start) && !visit.getEnteredAt().isAfter(end))
                        .sorted(java.util.Comparator.comparing(VisitLog::getEnteredAt))
                        .toList()
                : visitLogRepository.findByYardIdAndEnteredAtBetweenOrderByEnteredAtAsc(yardId, start, end);
        byte[] csv = exportService.toCsv(visits);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bitacora.csv\"")
                .body(csv);
    }
}
