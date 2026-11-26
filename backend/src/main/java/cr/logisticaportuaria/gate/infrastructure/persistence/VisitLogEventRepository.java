package cr.logisticaportuaria.gate.infrastructure.persistence;

import cr.logisticaportuaria.gate.domain.VisitLogEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


/**
 * Repository of the timeline of the visits ({@code visit_log_event}).
 */

public interface VisitLogEventRepository extends JpaRepository<VisitLogEvent, UUID> {

    /**
     * @param visitId visit of the events
     * @return the events of the visit, oldest first
     */
    List<VisitLogEvent> findByVisitIdOrderByAtAsc(UUID visitId);

    /**
     * Finds the visit that registered a reading (the timeline keeps the
     * {@code eventId} of the edge in the details).
     *
     * @param eventId identifier of the reading
     * @return the identifier of the visit, when it was found
     */
    @Query(value = """
            SELECT visit_id FROM visit_log_event
            WHERE detail ->> 'eventId' = :eventId
            ORDER BY at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findVisitIdByEventId(@Param("eventId") String eventId);

    /**
     * Finds the visit of a reading together with the result of its
     * reconciliation.
     *
     * @param eventId identifier of the reading
     * @return the link when the reading was registered in a visit
     */
    @Query(value = """
            SELECT vle.visit_id AS visitId, v.result AS result, v.reason AS reason
            FROM visit_log_event vle
            JOIN visit_log v ON v.id = vle.visit_id
            WHERE vle.detail ->> 'eventId' = :eventId
            ORDER BY vle.at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<VisitEventLink> findLinkByEventId(@Param("eventId") String eventId);
}
