package cr.logisticaportuaria.gate.domain;

import cr.logisticaportuaria.shared.domain.Direction;
import cr.logisticaportuaria.yard.domain.AppointmentType;
import cr.logisticaportuaria.yard.domain.ContainerStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Rules of the automatic reconciliation of a gate reading.
 *
 * <p>The class is pure: it receives the data and returns the decision, so the
 * rules can be tested without a database. The order of the rules is fixed and
 * the first match wins:
 *
 * <ol>
 *   <li>container under hold ({@code GATE_HOLD}) → rejected;</li>
 *   <li>confidence below the configured minimum → review;</li>
 *   <li>BIC code that does not pass the ISO 6346 validation → review;</li>
 *   <li>container that already left the yard → review;</li>
 *   <li>plate without an appointment in the window → review;</li>
 *   <li>BIC code different from the one expected by the appointment → review;</li>
 *   <li>otherwise → approved automatically.</li>
 * </ol>
 */
public final class GateReconciliationRules {

    private GateReconciliationRules() {
        // utility class
    }

    /**
     * Evaluates a reading.
     *
     * @param context data of the reading
     * @return the decision with its explanation in Spanish
     */
    public static ReconciliationDecision evaluate(ReconciliationContext context) {
        ReconciliationPolicy policy = context.policyOrDefault();
        ReconciliationContext.AppointmentMatch appointment = context.appointment();
        ReconciliationContext.ContainerMatch container = context.container();

        Direction direction = directionOf(appointment);
        // The plate of the appointment wins; otherwise the reading itself is reported.
        String plate = appointment != null
                ? appointment.plate()
                : context.kind() == ReadingKind.PLATE ? context.normalizedText() : null;
        String containerCode = container != null ? container.code() : context.normalizedText();

        if (context.kind() == ReadingKind.CONTAINER && container != null
                && container.status() == ContainerStatus.GATE_HOLD && policy.blockOnGateHold()) {
            return new ReconciliationDecision(
                    VisitResult.RECHAZADO,
                    "El contenedor %s se encuentra retenido en puerta (GATE_HOLD): no se permite la operación."
                            .formatted(container.code()),
                    direction,
                    appointmentId(appointment),
                    truckId(appointment),
                    container.id(),
                    plate,
                    containerCode);
        }

        if (context.confidence() != null && context.confidence() < policy.minConfidence()) {
            return new ReconciliationDecision(
                    VisitResult.REQUIERE_REVISION,
                    String.format(Locale.ROOT,
                            "La confianza de la lectura (%.2f) está por debajo del mínimo exigido (%.2f).",
                            context.confidence(), policy.minConfidence()),
                    direction,
                    appointmentId(appointment),
                    truckId(appointment),
                    containerId(container),
                    plate,
                    containerCode);
        }

        if (context.kind() == ReadingKind.CONTAINER && Boolean.FALSE.equals(context.isoValid())) {
            return new ReconciliationDecision(
                    VisitResult.REQUIERE_REVISION,
                    "El código BIC %s no supera la validación ISO 6346 (dígito de control incorrecto)."
                            .formatted(context.normalizedText()),
                    direction,
                    appointmentId(appointment),
                    truckId(appointment),
                    containerId(container),
                    plate,
                    containerCode);
        }

        if (context.kind() == ReadingKind.CONTAINER && container != null
                && container.status() == ContainerStatus.OUT) {
            return new ReconciliationDecision(
                    VisitResult.REQUIERE_REVISION,
                    "El contenedor %s ya fue despachado: no se encuentra en el patio."
                            .formatted(container.code()),
                    direction,
                    appointmentId(appointment),
                    truckId(appointment),
                    container.id(),
                    plate,
                    containerCode);
        }

        if (context.kind() == ReadingKind.PLATE && policy.requireAppointmentForPlates() && appointment == null) {
            return new ReconciliationDecision(
                    VisitResult.REQUIERE_REVISION,
                    "No hay cita vigente para la matrícula %s dentro de la ventana permitida (±%d min)."
                            .formatted(context.normalizedText(), policy.defaultWindowMinutes()),
                    direction,
                    null,
                    null,
                    null,
                    context.normalizedText(),
                    null);
        }

        if (context.kind() == ReadingKind.CONTAINER && appointment != null
                && appointment.containerCode() != null && !appointment.containerCode().isBlank()
                && !appointment.containerCode().equalsIgnoreCase(context.normalizedText())) {
            return new ReconciliationDecision(
                    VisitResult.REQUIERE_REVISION,
                    "La cita espera el contenedor %s y se leyó %s: requieren revisión manual."
                            .formatted(appointment.containerCode(), context.normalizedText()),
                    direction,
                    appointment.id(),
                    appointment.truckId(),
                    containerId(container),
                    plate,
                    containerCode);
        }

        return new ReconciliationDecision(
                VisitResult.AUTO_APROBADO,
                approvalReason(context, appointment, container, policy),
                direction,
                appointmentId(appointment),
                truckId(appointment),
                containerId(container),
                plate,
                containerCode);
    }

    /**
     * Tells whether an instant falls inside the window of an appointment.
     *
     * @param observedAt   instant of the reading
     * @param scheduledAt  scheduled instant of the appointment
     * @param windowMinutes size of the window (both sides)
     * @return true when the reading is inside the window (limits included)
     */
    public static boolean withinWindow(Instant observedAt, Instant scheduledAt, int windowMinutes) {
        if (observedAt == null || scheduledAt == null) {
            return false;
        }
        long difference = Math.abs(ChronoUnit.MINUTES.between(scheduledAt, observedAt));
        return difference <= windowMinutes;
    }

    private static String approvalReason(ReconciliationContext context,
            ReconciliationContext.AppointmentMatch appointment,
            ReconciliationContext.ContainerMatch container,
            ReconciliationPolicy policy) {
        String correction = context.corrected() ? " El OCR corrigió un carácter del código." : "";
        if (context.kind() == ReadingKind.PLATE) {
            int window = appointment != null && appointment.windowMinutes() > 0
                    ? appointment.windowMinutes()
                    : policy.defaultWindowMinutes();
            return ("Matrícula %s concilia con la cita %s de tipo %s (ventana ±%d min).%s")
                    .formatted(context.normalizedText(),
                            appointment == null ? "—" : "#" + appointment.id(),
                            appointment == null ? "—" : appointment.type(),
                            window,
                            correction);
        }
        if (container == null) {
            return ("Contenedor %s válido (ISO 6346). No está registrado en el patio: se registra como ingreso nuevo.%s")
                    .formatted(context.normalizedText(), correction);
        }
        if (appointment == null) {
            return ("Contenedor %s válido (ISO 6346) y se encuentra en el patio. No hay cita asociada.%s")
                    .formatted(container.code(), correction);
        }
        return ("Contenedor %s válido (ISO 6346) conciliado con la cita %s de tipo %s.%s")
                .formatted(container.code(),
                        "#" + appointment.id(),
                        appointment.type(),
                        correction);
    }

    private static Direction directionOf(ReconciliationContext.AppointmentMatch appointment) {
        if (appointment == null || appointment.type() == null) {
            return null;
        }
        return appointment.type() == AppointmentType.RETIRO ? Direction.OUT : Direction.IN;
    }

    private static java.util.UUID appointmentId(ReconciliationContext.AppointmentMatch appointment) {
        return appointment == null ? null : appointment.id();
    }

    private static java.util.UUID truckId(ReconciliationContext.AppointmentMatch appointment) {
        return appointment == null ? null : appointment.truckId();
    }

    private static java.util.UUID containerId(ReconciliationContext.ContainerMatch container) {
        return container == null ? null : container.id();
    }
}
