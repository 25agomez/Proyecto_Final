package cr.logisticaportuaria.gate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Raw reading received from the edge service ({@code gate_event}).
 *
 * <p>
 * The {@code event_id} of the edge provides the idempotency of the
 * ingestion: the same event received twice is stored once.
 */
@Entity
@Table(name = "gate_event")
public class GateEvent extends AbstractEntity {

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "yard_id", nullable = false)
    private UUID yardId;

    @Column(name = "gate_id", nullable = false)
    private UUID gateId;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private ReadingKind kind;

    @Column(name = "raw_text")
    private String rawText;

    @Column(name = "normalized_text")
    private String normalizedText;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "iso_valid")
    private Boolean isoValid;

    @Column(name = "corrected", nullable = false)
    private boolean corrected;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bbox", columnDefinition = "jsonb")
    private Map<String, Object> bbox = new HashMap<>();

    @Column(name = "evidence_path")
    private String evidencePath;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private EventSource source = EventSource.EDGE;

    protected GateEvent() {
        // for JPA
    }

    /**
     * Constructor of an event received from the edge.
     *
     * @param eventId        identifier provided by the edge (idempotency key)
     * @param yardId         yard of the reading
     * @param gateId         gate of the reading
     * @param observedAt     instant of the reading
     * @param kind           kind of the reading
     * @param rawText        text as it was read
     * @param normalizedText normalized text
     * @param confidence     confidence of the recognition
     * @param isoValid       result of the ISO 6346 validation
     * @param corrected      whether the edge corrected the reading
     * @param bbox           oriented bounding box
     * @param evidencePath   path of the evidence image
     * @param modelVersion   version of the models
     * @param latencyMs      latency of the processing
     * @param source         origin of the event
     */
    public GateEvent(UUID eventId, UUID yardId, UUID gateId, Instant observedAt, ReadingKind kind, String rawText,
            String normalizedText, BigDecimal confidence, Boolean isoValid, boolean corrected,
            Map<String, Object> bbox, String evidencePath, String modelVersion, Integer latencyMs,
            EventSource source) {
        this.eventId = eventId;
        this.yardId = yardId;
        this.gateId = gateId;
        this.observedAt = observedAt;
        this.kind = kind;
        this.rawText = rawText;
        this.normalizedText = normalizedText;
        this.confidence = confidence;
        this.isoValid = isoValid;
        this.corrected = corrected;
        this.bbox = bbox == null ? new HashMap<>() : new HashMap<>(bbox);
        this.evidencePath = evidencePath;
        this.modelVersion = modelVersion;
        this.latencyMs = latencyMs;
        this.source = source == null ? EventSource.EDGE : source;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getYardId() {
        return yardId;
    }

    public UUID getGateId() {
        return gateId;
    }

    public Instant getObservedAt() {
        return observedAt;
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

    public BigDecimal getConfidence() {
        return confidence;
    }

    public Boolean getIsoValid() {
        return isoValid;
    }

    public boolean isCorrected() {
        return corrected;
    }

    public Map<String, Object> getBbox() {
        return bbox;
    }

    public String getEvidencePath() {
        return evidencePath;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public EventSource getSource() {
        return source;
    }

    public void setNormalizedText(String normalizedText) {
        this.normalizedText = normalizedText;
    }

    public void setIsoValid(Boolean isoValid) {
        this.isoValid = isoValid;
    }

    public void setCorrected(boolean corrected) {
        this.corrected = corrected;
    }
}
