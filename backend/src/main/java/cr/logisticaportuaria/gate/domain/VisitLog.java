package cr.logisticaportuaria.gate.domain;

import cr.logisticaportuaria.shared.domain.Direction;
import cr.logisticaportuaria.shared.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Digital logbook of a visit to the yard ({@code visit_log}).
 *
 * <p>One row per truck operation: it registers the readings reconciled at the
 * gate, the state of the visit and the times used to compute the operational
 * indicators (waiting time, time in the yard and service time).
 */
@Entity
@Table(name = "visit_log")
public class VisitLog extends AbstractEntity implements AuditableEntity {

    @Column(name = "yard_id", nullable = false)
    private UUID yardId;

    @Column(name = "gate_id", nullable = false)
    private UUID gateId;

    @Column(name = "appointment_id")
    private UUID appointmentId;

    @Column(name = "truck_id")
    private UUID truckId;

    @Column(name = "container_id")
    private UUID containerId;

    @Column(name = "plate")
    private String plate;

    @Column(name = "container_code")
    private String containerCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private Direction direction = Direction.IN;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private VisitState state = VisitState.ENTRADA_PUERTA;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false)
    private VisitResult result = VisitResult.PENDIENTE;

    @Column(name = "reason")
    private String reason;

    @Column(name = "entered_at", nullable = false)
    private Instant enteredAt = Instant.now();

    @Column(name = "validated_at")
    private Instant validatedAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "processing_at")
    private Instant processingAt;

    @Column(name = "exited_at")
    private Instant exitedAt;

    @Column(name = "wait_seconds")
    private Integer waitSeconds;

    @Column(name = "yard_seconds")
    private Integer yardSeconds;

    @Column(name = "service_seconds")
    private Integer serviceSeconds;

    protected VisitLog() {
        // for JPA
    }

    /**
     * Constructor of a new visit.
     *
     * @param yardId     yard of the visit
     * @param gateId     gate of the visit
     * @param direction  direction of the visit
     * @param enteredAt  instant of the arrival
     */
    public VisitLog(UUID yardId, UUID gateId, Direction direction, Instant enteredAt) {
        this.yardId = yardId;
        this.gateId = gateId;
        this.direction = direction == null ? Direction.IN : direction;
        this.enteredAt = enteredAt == null ? Instant.now() : enteredAt;
    }

    /**
     * Applies a state transition and updates the timestamps of the stage.
     *
     * @param target      target state (validated before the change)
     * @param instant     instant of the transition
     * @throws cr.logisticaportuaria.shared.api.ConflictException when the transition is not allowed
     */
    public void transitionTo(VisitState target, Instant instant) {
        VisitStateMachine.assertTransition(state, target);
        Instant at = instant == null ? Instant.now() : instant;
        this.state = target;
        switch (target) {
            case VALIDADO -> {
                this.validatedAt = at;
                this.waitSeconds = (int) Duration.between(this.enteredAt, at).getSeconds();
            }
            case ASIGNADO -> this.assignedAt = at;
            case EN_PROCESO -> this.processingAt = at;
            case SALIDA_PUERTA -> {
                this.exitedAt = at;
                computeTimes(at);
            }
            default -> {
                // CANCELADO keeps the partial times.
            }
        }
    }

    /**
     * Recalculates the operational times of the visit.
     *
     * @param exitInstant instant of the exit
     */
    public void computeTimes(Instant exitInstant) {
        if (exitInstant == null) {
            return;
        }
        if (validatedAt != null) {
            this.waitSeconds = (int) Duration.between(enteredAt, validatedAt).getSeconds();
        }
        this.yardSeconds = (int) Duration.between(enteredAt, exitInstant).getSeconds();
        if (processingAt != null) {
            this.serviceSeconds = (int) Duration.between(processingAt, exitInstant).getSeconds();
        }
    }

    @Override
    public String auditEntityType() {
        return "visit_log";
    }

    @Override
    public UUID auditEntityId() {
        return getId();
    }


    public UUID getYardId() {
        return yardId;
    }

    public UUID getGateId() {
        return gateId;
    }

    public UUID getAppointmentId() {
        return appointmentId;
    }

    public void setAppointmentId(UUID appointmentId) {
        this.appointmentId = appointmentId;
    }

    public UUID getTruckId() {
        return truckId;
    }

    public void setTruckId(UUID truckId) {
        this.truckId = truckId;
    }

    public UUID getContainerId() {
        return containerId;
    }

    public void setContainerId(UUID containerId) {
        this.containerId = containerId;
    }

    public String getPlate() {
        return plate;
    }

    public void setPlate(String plate) {
        this.plate = plate;
    }

    public String getContainerCode() {
        return containerCode;
    }

    public void setContainerCode(String containerCode) {
        this.containerCode = containerCode;
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public VisitState getState() {
        return state;
    }

    public VisitResult getResult() {
        return result;
    }

    public void setResult(VisitResult result) {
        this.result = result;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getEnteredAt() {
        return enteredAt;
    }

    public void setEnteredAt(Instant enteredAt) {
        this.enteredAt = enteredAt;
    }

    public Instant getValidatedAt() {
        return validatedAt;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getProcessingAt() {
        return processingAt;
    }

    public Instant getExitedAt() {
        return exitedAt;
    }

    public Integer getWaitSeconds() {
        return waitSeconds;
    }

    public Integer getYardSeconds() {
        return yardSeconds;
    }

    public Integer getServiceSeconds() {
        return serviceSeconds;
    }
}
