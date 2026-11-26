package cr.logisticaportuaria.gate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.logisticaportuaria.shared.api.ConflictException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests of the state machine of the digital logbook.
 */
class VisitStateMachineTest {

    @Test
    @DisplayName("La secuencia feliz avanza de ENTRADA_PUERTA a SALIDA_PUERTA")
    void happyPathIsAllowed() {
        assertThat(VisitStateMachine.canTransition(VisitState.ENTRADA_PUERTA, VisitState.VALIDADO)).isTrue();
        assertThat(VisitStateMachine.canTransition(VisitState.VALIDADO, VisitState.ASIGNADO)).isTrue();
        assertThat(VisitStateMachine.canTransition(VisitState.ASIGNADO, VisitState.EN_PROCESO)).isTrue();
        assertThat(VisitStateMachine.canTransition(VisitState.EN_PROCESO, VisitState.SALIDA_PUERTA)).isTrue();
    }

    @Test
    @DisplayName("Se puede saltar la asignación y pasar de VALIDADO a EN_PROCESO solo si no hay asignacion")
    void skippingAssignmentIsNotAllowed() {
        assertThat(VisitStateMachine.canTransition(VisitState.VALIDADO, VisitState.EN_PROCESO)).isFalse();
        assertThat(VisitStateMachine.canTransition(VisitState.ENTRADA_PUERTA, VisitState.ASIGNADO)).isFalse();
        assertThat(VisitStateMachine.canTransition(VisitState.ENTRADA_PUERTA, VisitState.SALIDA_PUERTA)).isFalse();
    }

    @Test
    @DisplayName("Una transición inválida falla con un conflicto (HTTP 409)")
    void invalidTransitionThrowsConflict() {
        assertThatThrownBy(() -> VisitStateMachine.assertTransition(VisitState.VALIDADO, VisitState.SALIDA_PUERTA))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("VALIDADO")
                .hasMessageContaining("SALIDA_PUERTA");
    }

    @ParameterizedTest
    @EnumSource(value = VisitState.class, names = {"SALIDA_PUERTA", "CANCELADO"})
    @DisplayName("Los estados finales no admiten más transiciones")
    void finalStatesHaveNoTransitions(VisitState finalState) {
        assertThat(VisitStateMachine.allowedTargets(finalState)).isEmpty();
        for (VisitState target : VisitState.values()) {
            assertThat(VisitStateMachine.canTransition(finalState, target)).isFalse();
        }
    }

    @Test
    @DisplayName("Desde cualquier estado intermedio se puede cancelar la visita")
    void everyIntermediateStateCanBeCancelled() {
        Set<VisitState> intermediate = Set.of(
                VisitState.ENTRADA_PUERTA, VisitState.VALIDADO, VisitState.ASIGNADO, VisitState.EN_PROCESO);
        for (VisitState state : intermediate) {
            assertThat(VisitStateMachine.canTransition(state, VisitState.CANCELADO)).isTrue();
        }
    }

    @Test
    @DisplayName("Las transiciones permitidas son exactamente las definidas")
    void allowedTargetsAreTheExpectedOnes() {
        assertThat(VisitStateMachine.allowedTargets(VisitState.ENTRADA_PUERTA))
                .containsExactlyInAnyOrder(VisitState.VALIDADO, VisitState.CANCELADO);
        assertThat(VisitStateMachine.allowedTargets(VisitState.VALIDADO))
                .containsExactlyInAnyOrder(VisitState.ASIGNADO, VisitState.CANCELADO);
        assertThat(VisitStateMachine.allowedTargets(VisitState.ASIGNADO))
                .containsExactlyInAnyOrder(VisitState.EN_PROCESO, VisitState.CANCELADO);
        assertThat(VisitStateMachine.allowedTargets(VisitState.EN_PROCESO))
                .containsExactlyInAnyOrder(VisitState.SALIDA_PUERTA, VisitState.CANCELADO);
    }
}
