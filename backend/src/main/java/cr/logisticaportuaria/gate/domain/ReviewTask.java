package cr.logisticaportuaria.gate.domain;

import cr.logisticaportuaria.shared.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Task of the manual review queue ({@code review_task}).
 *
 * <p>Every reading that the reconciliation could not accept automatically
 * leaves a task here, with the reason and the raw text, so an operator can
 * confirm, correct or reject it.
 */
@Entity
@Table(name = "review_task")
public class ReviewTask extends AbstractEntity implements AuditableEntity {

    @Column(name = "gate_event_id", nullable = false)
    private UUID gateEventId;

    @Column(name = "visit_id")
    private UUID visitId;

    @Column(name = "yard_id", nullable = false)
    private UUID yardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private ReadingKind kind;

    @Column(name = "raw_text")
    private String rawText;

    @Column(name = "normalized_text")
    private String normalizedText;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReviewStatus status = ReviewStatus.PENDIENTE;

    @Column(name = "resolved_text")
    private String resolvedText;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * Creation instant. It is written by the database ({@code DEFAULT now()}), so
     * it is read only for the entity.
     */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ReviewTask() {
        // for JPA
    }

    /**
     * Constructor of a pending task.
     *
     * @param gateEventId event that generated the task
     * @param visitId     visit affected (may be null)
     * @param yardId      yard of the task
     * @param kind        kind of the reading
     * @param rawText     raw text of the reading
     * @param normalizedText normalized text of the reading
     * @param reason      reason of the manual review
     */
    public ReviewTask(UUID gateEventId, UUID visitId, UUID yardId, ReadingKind kind, String rawText,
            String normalizedText, String reason) {
        this.gateEventId = gateEventId;
        this.visitId = visitId;
        this.yardId = yardId;
        this.kind = kind;
        this.rawText = rawText;
        this.normalizedText = normalizedText;
        this.reason = reason;
    }

    /**
     * Resolves the task.
     *
     * @param newStatus   final status
     * @param resolvedText text confirmed or corrected by the operator
     * @param resolvedBy  operator that resolved the task
     * @param resolvedAt  instant of the resolution
     */
    public void resolve(ReviewStatus newStatus, String resolvedText, String resolvedBy, Instant resolvedAt) {
        this.status = newStatus;
        this.resolvedText = resolvedText;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt == null ? Instant.now() : resolvedAt;
    }

    @Override
    public String auditEntityType() {
        return "review_task";
    }

    @Override
    public UUID auditEntityId() {
        return getId();
    }


    public UUID getGateEventId() {
        return gateEventId;
    }

    public UUID getVisitId() {
        return visitId;
    }

    public void setVisitId(UUID visitId) {
        this.visitId = visitId;
    }

    public UUID getYardId() {
        return yardId;
    }

    public ReadingKind getKind() {
        return kind;
    }

    public String getRawText() {
        return rawText;
    }

    public String getNormalizedText() {
        return normalizedText;
    }

    public String getReason() {
        return reason;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public String getResolvedText() {
        return resolvedText;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
