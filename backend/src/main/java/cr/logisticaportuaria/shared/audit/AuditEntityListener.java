package cr.logisticaportuaria.shared.audit;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PreUpdate;

import org.springframework.stereotype.Component;

/**
 * JPA listener that registers the automatic changes of the master data in the
 * audit trail. It only acts on the entities that implement
 * {@link AuditableEntity}.
 */
@Component
public class AuditEntityListener {

    private final AuditService auditService;

    public AuditEntityListener(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Registers the creation of an entity.
     *
     * @param entity the persisted entity
     */
    @PostPersist
    public void onCreate(Object entity) {
        write(entity, "CREATE");
    }

    /**
     * Marks the entity as updated (the payload stores the new state).
     *
     * @param entity the updated entity
     */
    @PreUpdate
    public void onPreUpdate(Object entity) {
        // The state is written in @PostUpdate, when the new values are already set.
    }

    /**
     * Registers the update of an entity.
     *
     * @param entity the updated entity
     */
    @PostUpdate
    public void onUpdate(Object entity) {
        write(entity, "UPDATE");
    }

    private void write(Object entity, String action) {
        if (!(entity instanceof AuditableEntity auditable)) {
            return;
        }
        auditService.recordState(
                "sistema",
                action,
                auditable.auditEntityType(),
                auditable.auditEntityId(),
                null,
                entity);
    }
}
