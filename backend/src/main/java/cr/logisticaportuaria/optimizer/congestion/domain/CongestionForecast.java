package cr.logisticaportuaria.optimizer.congestion.domain;

import cr.logisticaportuaria.shared.persistence.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted congestion forecast ({@code congestion_forecast}).
 *
 * <p>One row per gate, horizon and computation. The scheduled job writes all the horizons
 * of a run with the same {@code computed_at} and keeps the history for
 * {@code app.congestion.retention-days} days.
 */
@Entity
@Table(name = "congestion_forecast")
public class CongestionForecast extends AbstractEntity {

    @Column(name = "yard_id", nullable = false)
    private UUID yardId;

    @Column(name = "gate_id", nullable = false)
    private UUID gateId;

    @Column(name = "horizon_min", nullable = false)
    private int horizonMin;

    @Column(name = "expected_wait_min", nullable = false, precision = 6, scale = 2)
    private BigDecimal expectedWaitMin;

    @Column(name = "p10", precision = 6, scale = 2)
    private BigDecimal p10;

    @Column(name = "p90", precision = 6, scale = 2)
    private BigDecimal p90;

    @Column(name = "queue_length", nullable = false)
    private int queueLength;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false)
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false)
    private ConfidenceLevel confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "drivers", nullable = false, columnDefinition = "jsonb")
    private List<String> drivers = new ArrayList<>();

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt = Instant.now();

    /** Required by JPA. */
    protected CongestionForecast() {
    }

    /**
     * Creates a row.
     *
     * @param yardId       yard of the gate
     * @param gateId       gate
     * @param horizonMin   horizon in minutes
     * @param prediction   prediction to persist
     * @param computedAt   instant of the run
     */
    public CongestionForecast(UUID yardId, UUID gateId, int horizonMin, CongestionPrediction prediction,
            Instant computedAt) {
        this.yardId = yardId;
        this.gateId = gateId;
        this.horizonMin = horizonMin;
        this.expectedWaitMin = toDecimal(prediction.expectedWaitMin());
        this.p10 = toDecimal(prediction.p10());
        this.p90 = toDecimal(prediction.p90());
        this.queueLength = (int) Math.round(prediction.queueLength());
        this.riskLevel = prediction.riskLevel();
        this.confidence = prediction.confidence();
        this.drivers = new ArrayList<>(prediction.drivers());
        this.model = prediction.model();
        this.computedAt = computedAt;
    }

    private static BigDecimal toDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    public UUID getYardId() {
        return yardId;
    }

    public UUID getGateId() {
        return gateId;
    }

    public int getHorizonMin() {
        return horizonMin;
    }

    public double getExpectedWaitMin() {
        return expectedWaitMin.doubleValue();
    }

    public Double getP10() {
        return p10 == null ? null : p10.doubleValue();
    }

    public Double getP90() {
        return p90 == null ? null : p90.doubleValue();
    }

    public int getQueueLength() {
        return queueLength;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public ConfidenceLevel getConfidence() {
        return confidence;
    }

    public List<String> getDrivers() {
        return List.copyOf(drivers);
    }

    public String getModel() {
        return model;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
