package cr.logisticaportuaria.shared.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;


/**
 * Repository of the audit trail.
 */

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    /**
     * Lists the entries of an entity.
     *
     * @param entityType type of the entity
     * @param entityId   identifier of the entity
     * @param pageable   pagination
     * @return the entries
     */
    Page<AuditLogEntry> findByEntityTypeAndEntityId(String entityType, String entityId, Pageable pageable);

    /**
     * Lists the entries of a yard.
     *
     * @param yardId   identifier of the yard
     * @param pageable pagination
     * @return the entries
     */
    Page<AuditLogEntry> findByYardId(UUID yardId, Pageable pageable);

    /**
     * Lists the most recent entries.
     *
     * @param pageable pagination
     * @return the entries
     */
    List<AuditLogEntry> findAllByOrderByAtDesc(Pageable pageable);
}
