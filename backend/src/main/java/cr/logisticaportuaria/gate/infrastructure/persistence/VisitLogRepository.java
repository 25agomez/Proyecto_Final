package cr.logisticaportuaria.gate.infrastructure.persistence;

import cr.logisticaportuaria.gate.domain.VisitLog;
import cr.logisticaportuaria.gate.domain.VisitResult;
import cr.logisticaportuaria.gate.domain.VisitState;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


/**
 * Repository of the digital logbook ({@code visit_log}).
 */

public interface VisitLogRepository extends JpaRepository<VisitLog, UUID> {

    /**
     * Visits that are still open (not exited nor cancelled) of a yard.
     *
     * @param yardId   yard of the visits
     * @param states   states considered open
     * @param pageable pagination
     * @return the open visits
     */
    Page<VisitLog> findByYardIdAndStateIn(UUID yardId, Collection<VisitState> states, Pageable pageable);

    /**
     * Open visits of a gate, most recent first.
     *
     * @param gateId   gate of the visits
     * @param states   states considered open
     * @param pageable pagination
     * @return the open visits
     */
    List<VisitLog> findByGateIdAndStateInOrderByEnteredAtDesc(UUID gateId, Collection<VisitState> states);

    /**
     * Open visit of a truck in a yard (the last one that was not closed).
     *
     * @param yardId yard of the visit
     * @param truckId truck of the visit
     * @param states states considered open
     * @return the visit, when there is one
     */
    List<VisitLog> findByYardIdAndTruckIdAndStateInOrderByEnteredAtDesc(UUID yardId, UUID truckId,
            Collection<VisitState> states);

    /**
     * Visits with the given filters.
     *
     * @param yardId        yard of the visits (may be null)
     * @param state         state of the visits (may be null)
     * @param result        result of the reconciliation (may be null)
     * @param plate         plate of the truck (may be null)
     * @param containerCode container of the visit (may be null)
     * @param from          lower bound of the period (may be null)
     * @param to            upper bound of the period (may be null)
     * @param pageable      pagination
     * @return the matching visits
     */
    @Query("""
            SELECT v FROM VisitLog v
            WHERE (cast(:yardId as uuid) IS NULL OR v.yardId = :yardId)
              AND (cast(:state as string) IS NULL OR v.state = :state)
              AND (cast(:result as string) IS NULL OR v.result = :result)
              AND (cast(:plate as string) IS NULL OR upper(v.plate) = upper(cast(:plate as string)))
              AND (cast(:containerCode as string) IS NULL
                   OR upper(v.containerCode) = upper(cast(:containerCode as string)))
              AND (cast(:from as instant) IS NULL OR v.enteredAt >= :from)
              AND (cast(:to as instant) IS NULL OR v.enteredAt <= :to)
            """)
    Page<VisitLog> search(@Param("yardId") UUID yardId,
            @Param("state") VisitState state,
            @Param("result") VisitResult result,
            @Param("plate") String plate,
            @Param("containerCode") String containerCode,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /**
     * Visits of a yard in a period (used by the CSV export).
     *
     * @param yardId yard of the visits
     * @param from   lower bound of the period
     * @param to     upper bound of the period
     * @return the visits
     */
    List<VisitLog> findByYardIdAndEnteredAtBetweenOrderByEnteredAtAsc(UUID yardId, Instant from, Instant to);

    /**
     * @param yardId yard of the visits
     * @param result result of the reconciliation
     * @return number of visits with the result
     */
    long countByYardIdAndResult(UUID yardId, VisitResult result);
}
