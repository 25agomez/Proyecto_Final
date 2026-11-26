package cr.logisticaportuaria.gate.api;

import cr.logisticaportuaria.gate.application.ReviewTaskService;
import cr.logisticaportuaria.gate.application.dto.ResolveReviewRequest;
import cr.logisticaportuaria.gate.application.dto.ReviewTaskResponse;
import cr.logisticaportuaria.gate.domain.ReviewStatus;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API of the manual review queue.
 */
@RestController
@RequestMapping("/api/v1/review-tasks")
public class ReviewTaskController {

    private final ReviewTaskService reviewTaskService;

    /**
     * Constructor.
     *
     * @param reviewTaskService service of the queue
     */
    public ReviewTaskController(ReviewTaskService reviewTaskService) {
        this.reviewTaskService = reviewTaskService;
    }

    /**
     * Lists the tasks of the queue.
     *
     * @param status status of the tasks (optional)
     * @param yardId yard of the tasks (optional)
     * @return the tasks
     */
    @GetMapping
    public List<ReviewTaskResponse> list(
            @RequestParam(required = false) ReviewStatus status,
            @RequestParam(required = false) UUID yardId) {
        return reviewTaskService.list(yardId, status);
    }

    /**
     * Resolves a task: the reading is approved, corrected or rejected.
     *
     * @param id      identifier of the task
     * @param request resolution of the operator
     * @return the resolved task
     */
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('OPERADOR_PUERTA', 'SUPERVISOR', 'ADMIN')")
    public ReviewTaskResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveReviewRequest request) {
        return reviewTaskService.resolve(id, request);
    }
}
