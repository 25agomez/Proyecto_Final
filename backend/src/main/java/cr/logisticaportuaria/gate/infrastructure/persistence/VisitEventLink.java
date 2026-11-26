package cr.logisticaportuaria.gate.infrastructure.persistence;

import java.util.UUID;

/**
 * Link between a reading of the edge and the visit of the digital logbook.
 *
 * <p>The timeline of the visit ({@code visit_log_event}) keeps the
 * {@code eventId} of the edge in its details, so the link is resolved with the
 * JSONB operator of PostgreSQL.
 */
public interface VisitEventLink {

    /**
     * @return identifier of the visit
     */
    UUID getVisitId();

    /**
     * @return result of the reconciliation
     */
    String getResult();

    /**
     * @return explanation of the result
     */
    String getReason();
}
