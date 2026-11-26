package cr.logisticaportuaria.yard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Appointment ({@code appointment}): the booking of a truck for an operation
 * inside the yard.
 *
 * <p>The reconciliation of the gate readings uses the scheduled instant and
 * the window (in minutes) to know if a reading belongs to the appointment.
 */
@Entity
@Table(name = "appointment")
public class Appointment extends AbstractEntity {

    @Column(name = "yard_id", nullable = false)
    private UUID yardId;

    @Column(name = "gate_id")
    private UUID gateId;

    @Column(name = "truck_id")
    private UUID truckId;

    @Enumerated(EnumType.STRING)
    @Column(name = "appointment_type", nullable = false)
    private AppointmentType appointmentType = AppointmentType.INGRESO;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt = Instant.now();

    @Column(name = "window_minutes", nullable = false)
    private int windowMinutes = 30;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AppointmentStatus status = AppointmentStatus.PENDIENTE;

    @Column(name = "container_code")
    private String containerCode;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Appointment() {
        // for JPA
    }

    /**
     * Constructor.
     *
     * @param yardId          yard of the appointment
     * @param gateId          gate assigned to the appointment (may be null)
     * @param truckId         truck of the appointment (may be null)
     * @param appointmentType INGRESO or RETIRO
     * @param scheduledAt     scheduled instant
     * @param windowMinutes   window of the appointment
     * @param containerCode   container expected (may be null)
     * @param notes           free notes (may be null)
     */
    public Appointment(UUID yardId, UUID gateId, UUID truckId, AppointmentType appointmentType, Instant scheduledAt,
            int windowMinutes, String containerCode, String notes) {
        this.yardId = yardId;
        this.gateId = gateId;
        this.truckId = truckId;
        this.appointmentType = appointmentType == null ? AppointmentType.INGRESO : appointmentType;
        if (scheduledAt != null) {
            this.scheduledAt = scheduledAt;
        }
        if (windowMinutes > 0) {
            this.windowMinutes = windowMinutes;
        }
        this.containerCode = containerCode;
        this.notes = notes;
    }


    public UUID getYardId() {
        return yardId;
    }

    public UUID getGateId() {
        return gateId;
    }

    public void setGateId(UUID gateId) {
        this.gateId = gateId;
    }

    public UUID getTruckId() {
        return truckId;
    }

    public void setTruckId(UUID truckId) {
        this.truckId = truckId;
    }

    public AppointmentType getAppointmentType() {
        return appointmentType;
    }

    public void setAppointmentType(AppointmentType appointmentType) {
        this.appointmentType = appointmentType;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(int windowMinutes) {
        this.windowMinutes = windowMinutes;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public void setStatus(AppointmentStatus status) {
        this.status = status;
    }

    public String getContainerCode() {
        return containerCode;
    }

    public void setContainerCode(String containerCode) {
        this.containerCode = containerCode;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
