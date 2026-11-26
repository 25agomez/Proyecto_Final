package cr.logisticaportuaria.gate.domain;

import cr.logisticaportuaria.yard.domain.AppointmentType;
import cr.logisticaportuaria.yard.domain.ContainerStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Data needed to reconcile a reading. The class only carries data (no
 * behaviour) so the rules can be tested without a database.
 *
 * @param kind          kind of the reading
 * @param normalizedText normalized text of the reading
 * @param confidence    confidence reported by the OCR (may be null)
 * @param isoValid      result of the ISO 6346 validation (null for plates)
 * @param corrected     whether the edge corrected the reading
 * @param observedAt    instant of the reading
 * @param appointment   appointment that matched the reading (may be null)
 * @param container     container that matched the BIC code (may be null)
 * @param policy        parameters of the reconciliation
 */
public record ReconciliationContext(
        ReadingKind kind,
        String normalizedText,
        Double confidence,
        Boolean isoValid,
        boolean corrected,
        Instant observedAt,
        AppointmentMatch appointment,
        ContainerMatch container,
        ReconciliationPolicy policy) {

    /**
     * Appointment that can be matched with a reading.
     *
     * @param id             identifier of the appointment
     * @param truckId        truck of the appointment
     * @param plate          plate of the truck
     * @param type           INGRESO or RETIRO
     * @param scheduledAt    scheduled instant
     * @param windowMinutes  window of the appointment
     * @param containerCode  container expected by the appointment (may be null)
     */
    public record AppointmentMatch(
            UUID id,
            UUID truckId,
            String plate,
            AppointmentType type,
            Instant scheduledAt,
            int windowMinutes,
            String containerCode) {
    }

    /**
     * Container that can be matched with a BIC code.
     *
     * @param id     identifier of the container
     * @param code   BIC code
     * @param status status of the container
     */
    public record ContainerMatch(
            UUID id,
            String code,
            ContainerStatus status) {
    }

    /**
     * @return the policy of the context (never null)
     */
    public ReconciliationPolicy policyOrDefault() {
        return policy == null ? ReconciliationPolicy.DEFAULT : policy;
    }

    /**
     * @param appointment the appointment that matched
     * @return a copy of the context with the given appointment
     */
    public ReconciliationContext withAppointment(AppointmentMatch appointment) {
        return new ReconciliationContext(kind, normalizedText, confidence, isoValid, corrected,
                observedAt, appointment, container, policy);
    }

    /**
     * @param container the container that matched
     * @return a copy of the context with the given container
     */
    public ReconciliationContext withContainer(ContainerMatch container) {
        return new ReconciliationContext(kind, normalizedText, confidence, isoValid, corrected,
                observedAt, appointment, container, policy);
    }

    /**
     * @param policy the parameters of the reconciliation
     * @return a copy of the context with the given policy
     */
    public ReconciliationContext withPolicy(ReconciliationPolicy policy) {
        return new ReconciliationContext(kind, normalizedText, confidence, isoValid, corrected,
                observedAt, appointment, container, policy);
    }
}
