package cr.logisticaportuaria.gate.application;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import cr.logisticaportuaria.gate.domain.ReconciliationPolicy;

/**
 * Configuration of the gate module ({@code app.gate.*}).
 */
@Validated
@ConfigurationProperties(prefix = "app.gate")
public class GateProperties {

    /** Minimum confidence accepted for an automatic approval. */
    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double minConfidence = 0.75;

    /** Default window (in minutes) used to match a reading with an appointment. */
    @Min(1)
    private int windowMinutes = 30;

    /** Whether a plate without an appointment in the window must be reviewed. */
    private boolean requireAppointmentForPlates = true;

    /** Whether a container under hold ({@code GATE_HOLD}) must be rejected. */
    private boolean blockOnGateHold = true;

    /** Window (in minutes) used to attach a container reading to an open visit. */
    @Min(1)
    private int visitMatchWindowMinutes = 30;

    /**
     * @return the parameters of the reconciliation
     */
    public ReconciliationPolicy toPolicy() {
        return new ReconciliationPolicy(
                minConfidence == null ? ReconciliationPolicy.DEFAULT.minConfidence() : minConfidence,
                windowMinutes,
                requireAppointmentForPlates,
                blockOnGateHold);
    }

    public Double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(Double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(int windowMinutes) {
        this.windowMinutes = windowMinutes;
    }

    public boolean isRequireAppointmentForPlates() {
        return requireAppointmentForPlates;
    }

    public void setRequireAppointmentForPlates(boolean requireAppointmentForPlates) {
        this.requireAppointmentForPlates = requireAppointmentForPlates;
    }

    public boolean isBlockOnGateHold() {
        return blockOnGateHold;
    }

    public void setBlockOnGateHold(boolean blockOnGateHold) {
        this.blockOnGateHold = blockOnGateHold;
    }

    public int getVisitMatchWindowMinutes() {
        return visitMatchWindowMinutes;
    }

    public void setVisitMatchWindowMinutes(int visitMatchWindowMinutes) {
        this.visitMatchWindowMinutes = visitMatchWindowMinutes;
    }
}
