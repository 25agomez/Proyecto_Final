package cr.logisticaportuaria.gate.domain;

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
 * Event of the timeline of a visit ({@code visit_log_event}): every stage of
 * the digital logbook leaves a trace with its actor.
 */
@Entity
@Table(name = "visit_log_event")
public class VisitLogEvent extends AbstractEntity {

    @Column(name = "visit_id", nullable = false)
    private UUID visitId;

    @Column(name = "stage", nullable = false)
    private String stage;

    @Column(name = "at", nullable = false)
    private Instant at = Instant.now();

    @Column(name = "actor")
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> detail = new HashMap<>();

    protected VisitLogEvent() {
        // for JPA
    }

    /**
     * Constructor.
     *
     * @param visitId visit of the event
     * @param stage   stage that was reached
     * @param at      instant of the event
     * @param actor   who registered it
     * @param detail  details of the event
     */
    public VisitLogEvent(UUID visitId, String stage, Instant at, String actor, Map<String, Object> detail) {
        this.visitId = visitId;
        this.stage = stage;
        this.at = at == null ? Instant.now() : at;
        this.actor = actor;
        this.detail = detail == null ? new HashMap<>() : new HashMap<>(detail);
    }


    public UUID getVisitId() {
        return visitId;
    }

    public String getStage() {
        return stage;
    }

    public Instant getAt() {
        return at;
    }

    public String getActor() {
        return actor;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }
}
