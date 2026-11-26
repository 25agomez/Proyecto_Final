package cr.logisticaportuaria.gate.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cr.logisticaportuaria.gate.domain.ReconciliationContext.AppointmentMatch;
import cr.logisticaportuaria.gate.domain.ReconciliationContext.ContainerMatch;
import cr.logisticaportuaria.shared.domain.Direction;
import cr.logisticaportuaria.yard.domain.AppointmentType;
import cr.logisticaportuaria.yard.domain.ContainerStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the reconciliation rules.
 *
 * <p>The rules are pure, so every scenario is checked without a database.
 */
class GateReconciliationRulesTest {

    private static final Instant NOW = Instant.parse("2026-02-22T12:00:00Z");

    private static final ReconciliationPolicy POLICY =
            new ReconciliationPolicy(0.75, 30, true, true);

    @Test
    @DisplayName("Un contenedor retenido en puerta (GATE_HOLD) se rechaza")
    void rejectsContainerUnderGateHold() {
        ReconciliationDecision decision = evaluate(ReadingKind.CONTAINER, "CSQU3054383",
                context(ReadingKind.CONTAINER, "CSQU3054383", 0.99, true, false)
                        .withContainer(new ContainerMatch(UUID.randomUUID(), "CSQU3054383", ContainerStatus.GATE_HOLD)));

        assertThat(decision.result()).isEqualTo(VisitResult.RECHAZADO);
        assertThat(decision.reason()).contains("GATE_HOLD").contains("CSQU3054383");
        assertThat(decision.requiresReview()).isFalse();
    }

    @Test
    @DisplayName("Con la política que no bloquea el GATE_HOLD, el contenedor se aprueba")
    void approvesGateHoldWhenPolicyAllows() {
        ReconciliationPolicy permissive = new ReconciliationPolicy(0.75, 30, true, false);
        ReconciliationDecision decision = evaluate(ReadingKind.CONTAINER, "CSQU3054383",
                context(ReadingKind.CONTAINER, "CSQU3054383", 0.99, true, false)
                        .withPolicy(permissive)
                        .withContainer(new ContainerMatch(UUID.randomUUID(), "CSQU3054383", ContainerStatus.GATE_HOLD)));

        assertThat(decision.result()).isEqualTo(VisitResult.AUTO_APROBADO);
    }

    @Test
    @DisplayName("Una confianza por debajo del mínimo exige revisión manual")
    void lowConfidenceRequiresReview() {
        ReconciliationDecision decision = evaluate(ReadingKind.PLATE, "ABC123",
                context(ReadingKind.PLATE, "ABC123", 0.50, null, false));

        assertThat(decision.result()).isEqualTo(VisitResult.REQUIERE_REVISION);
        assertThat(decision.reason()).contains("confianza").contains("0.50").contains("0.75");
        assertThat(decision.requiresReview()).isTrue();
    }

    @Test
    @DisplayName("Un BIC que no pasa la validación ISO 6346 exige revisión manual")
    void invalidIsoRequiresReview() {
        ReconciliationDecision decision = evaluate(ReadingKind.CONTAINER, "CSQU3054380",
                context(ReadingKind.CONTAINER, "CSQU3054380", 0.98, false, false));

        assertThat(decision.result()).isEqualTo(VisitResult.REQUIERE_REVISION);
        assertThat(decision.reason()).contains("ISO 6346").contains("CSQU3054380");
    }

    @Test
    @DisplayName("Un contenedor que ya salió del patio exige revisión manual")
    void containerAlreadyOutRequiresReview() {
        ReconciliationDecision decision = evaluate(ReadingKind.CONTAINER, "CSQU3054383",
                context(ReadingKind.CONTAINER, "CSQU3054383", 0.97, true, false)
                        .withContainer(new ContainerMatch(UUID.randomUUID(), "CSQU3054383", ContainerStatus.OUT)));

        assertThat(decision.result()).isEqualTo(VisitResult.REQUIERE_REVISION);
        assertThat(decision.reason()).contains("despachado");
    }

    @Test
    @DisplayName("Una matrícula sin cita vigente exige revisión manual")
    void plateWithoutAppointmentRequiresReview() {
        ReconciliationDecision decision = evaluate(ReadingKind.PLATE, "ABC123",
                context(ReadingKind.PLATE, "ABC123", 0.99, null, false));

        assertThat(decision.result()).isEqualTo(VisitResult.REQUIERE_REVISION);
        assertThat(decision.reason()).contains("cita vigente").contains("ABC123");
    }

    @Test
    @DisplayName("Una matrícula con cita dentro de la ventana se aprueba automáticamente")
    void plateWithAppointmentInWindowIsApproved() {
        UUID appointmentId = UUID.randomUUID();
        UUID truckId = UUID.randomUUID();
        AppointmentMatch appointment = new AppointmentMatch(
                appointmentId, truckId, "ABC123", AppointmentType.INGRESO, NOW, 30, null);

        ReconciliationDecision decision = evaluate(ReadingKind.PLATE, "ABC123",
                context(ReadingKind.PLATE, "ABC123", 0.99, null, false).withAppointment(appointment));

        assertThat(decision.result()).isEqualTo(VisitResult.AUTO_APROBADO);
        assertThat(decision.appointmentId()).isEqualTo(appointmentId);
        assertThat(decision.truckId()).isEqualTo(truckId);
        assertThat(decision.direction()).isEqualTo(Direction.IN);
        assertThat(decision.plate()).isEqualTo("ABC123");
        assertThat(decision.reason()).contains("concilia con la cita");
    }

    @Test
    @DisplayName("Una cita de retiro define la dirección OUT")
    void withdrawalAppointmentMeansOutbound() {
        AppointmentMatch appointment = new AppointmentMatch(
                UUID.randomUUID(), UUID.randomUUID(), "ABC123", AppointmentType.RETIRO, NOW, 30, null);

        ReconciliationDecision decision = evaluate(ReadingKind.PLATE, "ABC123",
                context(ReadingKind.PLATE, "ABC123", 0.99, null, false).withAppointment(appointment));

        assertThat(decision.direction()).isEqualTo(Direction.OUT);
    }

    @Test
    @DisplayName("El BIC leído que no corresponde al de la cita exige revisión manual")
    void containerDifferentFromAppointmentRequiresReview() {
        AppointmentMatch appointment = new AppointmentMatch(
                UUID.randomUUID(), UUID.randomUUID(), "ABC123", AppointmentType.INGRESO, NOW, 30, "CSQU3054383");

        ReconciliationDecision decision = evaluate(ReadingKind.CONTAINER, "MSCU1234565",
                context(ReadingKind.CONTAINER, "MSCU1234565", 0.99, true, false).withAppointment(appointment));

        assertThat(decision.result()).isEqualTo(VisitResult.REQUIERE_REVISION);
        assertThat(decision.reason()).contains("CSQU3054383").contains("MSCU1234565");
    }

    @Test
    @DisplayName("Un contenedor válido que está en el patio se aprueba sin cita")
    void validContainerInYardIsApproved() {
        UUID containerId = UUID.randomUUID();
        ReconciliationDecision decision = evaluate(ReadingKind.CONTAINER, "CSQU3054383",
                context(ReadingKind.CONTAINER, "CSQU3054383", 0.99, true, false)
                        .withContainer(new ContainerMatch(containerId, "CSQU3054383", ContainerStatus.IN_YARD)));

        assertThat(decision.result()).isEqualTo(VisitResult.AUTO_APROBADO);
        assertThat(decision.containerId()).isEqualTo(containerId);
        assertThat(decision.containerCode()).isEqualTo("CSQU3054383");
        assertThat(decision.reason()).contains("se encuentra en el patio");
    }

    @Test
    @DisplayName("Un contenedor válido que no está registrado se aprueba como ingreso nuevo")
    void unknownContainerIsApprovedAsNewEntry() {
        ReconciliationDecision decision = evaluate(ReadingKind.CONTAINER, "CSQU3054383",
                context(ReadingKind.CONTAINER, "CSQU3054383", 0.99, true, false));

        assertThat(decision.result()).isEqualTo(VisitResult.AUTO_APROBADO);
        assertThat(decision.containerId()).isNull();
        assertThat(decision.reason()).contains("ingreso nuevo");
    }

    @Test
    @DisplayName("Sin exigencia de cita, una matrícula sin cita se aprueba")
    void plateWithoutAppointmentIsApprovedWhenNotRequired() {
        ReconciliationPolicy permissive = new ReconciliationPolicy(0.75, 30, false, true);
        ReconciliationDecision decision = evaluate(ReadingKind.PLATE, "ABC123",
                context(ReadingKind.PLATE, "ABC123", 0.99, null, false).withPolicy(permissive));

        assertThat(decision.result()).isEqualTo(VisitResult.AUTO_APROBADO);
        assertThat(decision.plate()).isEqualTo("ABC123");
    }

    @Test
    @DisplayName("La corrección del OCR se menciona en el motivo de la aprobación")
    void correctedReadingIsMentioned() {
        ReconciliationDecision decision = evaluate(ReadingKind.CONTAINER, "CSQU3054383",
                context(ReadingKind.CONTAINER, "CSQU3054383", 0.99, true, true));

        assertThat(decision.result()).isEqualTo(VisitResult.AUTO_APROBADO);
        assertThat(decision.reason()).contains("corrigió un carácter");
    }

    @Test
    @DisplayName("La ventana de la cita incluye exactamente los límites")
    void windowIncludesTheLimits() {
        assertThat(GateReconciliationRules.withinWindow(NOW, NOW, 30)).isTrue();
        assertThat(GateReconciliationRules.withinWindow(
                NOW.plus(30, ChronoUnit.MINUTES), NOW, 30)).isTrue();
        assertThat(GateReconciliationRules.withinWindow(
                NOW.minus(30, ChronoUnit.MINUTES), NOW, 30)).isTrue();
        assertThat(GateReconciliationRules.withinWindow(
                NOW.plus(31, ChronoUnit.MINUTES), NOW, 30)).isFalse();
        assertThat(GateReconciliationRules.withinWindow(
                NOW.minus(31, ChronoUnit.MINUTES), NOW, 30)).isFalse();
        assertThat(GateReconciliationRules.withinWindow(NOW, null, 30)).isFalse();
    }

    @Test
    @DisplayName("El orden de las reglas: el GATE_HOLD gana sobre la confianza baja")
    void gateHoldWinsOverLowConfidence() {
        ReconciliationDecision decision = evaluate(ReadingKind.CONTAINER, "CSQU3054383",
                context(ReadingKind.CONTAINER, "CSQU3054383", 0.10, false, false)
                        .withContainer(new ContainerMatch(UUID.randomUUID(), "CSQU3054383", ContainerStatus.GATE_HOLD)));

        assertThat(decision.result()).isEqualTo(VisitResult.RECHAZADO);
    }

    @Test
    @DisplayName("Una lectura sin confianza no se penaliza")
    void missingConfidenceDoesNotBlockApproval() {
        ReconciliationDecision decision = evaluate(ReadingKind.CONTAINER, "CSQU3054383",
                context(ReadingKind.CONTAINER, "CSQU3054383", null, true, false));

        assertThat(decision.result()).isEqualTo(VisitResult.AUTO_APROBADO);
    }

    private static ReconciliationDecision evaluate(ReadingKind kind, String text, ReconciliationContext context) {
        assertThat(context.kind()).isEqualTo(kind);
        assertThat(context.normalizedText()).isEqualTo(text);
        return GateReconciliationRules.evaluate(context);
    }

    private static ReconciliationContext context(ReadingKind kind,
            String text,
            Double confidence,
            Boolean isoValid,
            boolean corrected) {
        return new ReconciliationContext(kind, text, confidence, isoValid, corrected, NOW, null, null, POLICY);
    }
}
