package cr.logisticaportuaria.gate.infrastructure.persistence;

import cr.logisticaportuaria.gate.domain.GateEvent;
import cr.logisticaportuaria.gate.domain.ReadingKind;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


/**
 * Repository of the readings received from the edge ({@code gate_event}).
 */

public interface GateEventRepository extends JpaRepository<GateEvent, UUID> {

    /**
     * @param eventId identifier provided by the edge
     * @return the event, when it was already ingested
     */
    Optional<GateEvent> findByEventId(UUID eventId);

    /**
     * Readings with the given filters.
     *
     * @param gateId  gate of the readings (may be null)
     * @param kind    kind of the reading (may be null)
     * @param from    lower bound of the period (may be null)
     * @param to      upper bound of the period (may be null)
     * @param text    part of the normalized text (may be null)
     * @param pageable pagination
     * @return the matching readings
     */
    @Query("""
            SELECT e FROM GateEvent e
            WHERE (cast(:gateId as uuid) IS NULL OR e.gateId = :gateId)
              AND (cast(:kind as string) IS NULL OR e.kind = :kind)
              AND (cast(:from as instant) IS NULL OR e.observedAt >= :from)
              AND (cast(:to as instant) IS NULL OR e.observedAt <= :to)
              AND (cast(:text as string) IS NULL
                   OR upper(e.normalizedText) LIKE upper(concat('%', cast(:text as string), '%')))
            """)
    Page<GateEvent> search(@Param("gateId") UUID gateId,
            @Param("kind") ReadingKind kind,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("text") String text,
            Pageable pageable);
}
