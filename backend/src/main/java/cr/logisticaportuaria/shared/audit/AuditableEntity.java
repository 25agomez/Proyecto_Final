package cr.logisticaportuaria.shared.audit;

import java.util.UUID;

/**
 * Contract of the entities that are registered automatically in the audit trail.
 */
public interface AuditableEntity {

    /**
     * @return the name of the entity type stored in {@code audit_log.entity_type}
     */
    String auditEntityType();

    /**
     * @return the identifier of the entity
     */
    UUID auditEntityId();
}
