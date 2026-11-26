package cr.logisticaportuaria.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Entry of the audit trail ({@code audit_log}): who changed what and when.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry extends AbstractEntity {

    @Column(name = "yard_id")
    private UUID yardId;

    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new HashMap<>();

    @Column(name = "at", nullable = false)
    private Instant at = Instant.now();

    protected AuditLogEntry() {
        // for JPA
    }

    /**
     * Constructor.
     *
     * @param yardId     yard of the operation (may be null)
     * @param actor      who performed the operation
     * @param action     what was done (CREATE, UPDATE, DELETE, business action)
     * @param entityType type of the affected entity
     * @param entityId   identifier of the affected entity
     * @param payload    details of the change
     */
    public AuditLogEntry(UUID yardId, String actor, String action, String entityType, String entityId,
            Map<String, Object> payload) {
        this.yardId = yardId;
        this.actor = actor;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.payload = payload == null ? new HashMap<>() : new HashMap<>(payload);
    }


    public UUID getYardId() {
        return yardId;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Instant getAt() {
        return at;
    }
}
