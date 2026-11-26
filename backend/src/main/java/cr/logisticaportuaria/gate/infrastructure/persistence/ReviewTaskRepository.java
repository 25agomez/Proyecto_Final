package cr.logisticaportuaria.gate.infrastructure.persistence;

import cr.logisticaportuaria.gate.domain.ReviewStatus;
import cr.logisticaportuaria.gate.domain.ReviewTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


/**
 * Repository of the manual review queue ({@code review_task}).
 */

public interface ReviewTaskRepository extends JpaRepository<ReviewTask, UUID> {

    /**
     * @param yardId   yard of the tasks (may be null)
     * @param status   status of the tasks (may be null)
     * @param pageable pagination
     * @return the matching tasks
     */
    @Query("""
            SELECT t FROM ReviewTask t
            WHERE (cast(:yardId as uuid) IS NULL OR t.yardId = :yardId)
              AND (cast(:status as string) IS NULL OR t.status = :status)
            """)
    Page<ReviewTask> search(@Param("yardId") UUID yardId,
            @Param("status") ReviewStatus status,
            Pageable pageable);

    /**
     * @param gateEventId event that generated the task
     * @param status      status of the task
     * @return the task, when it exists
     */
    Optional<ReviewTask> findByGateEventIdAndStatus(UUID gateEventId, ReviewStatus status);

    /**
     * @param yardId yard of the tasks
     * @param status status of the tasks
     * @return the number of tasks with the status
     */
    long countByYardIdAndStatus(UUID yardId, ReviewStatus status);

    /**
     * @param yardId yard of the tasks
     * @return the pending tasks, oldest first
     */
    List<ReviewTask> findByYardIdAndStatusOrderByCreatedAtAsc(UUID yardId, ReviewStatus status);
}
