package cr.logisticaportuaria.gate.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import cr.logisticaportuaria.shared.api.ConflictException;

/**
 * State machine of the digital logbook.
 *
 * <pre>
 * ENTRADA_PUERTA -&gt; VALIDADO -&gt; ASIGNADO -&gt; EN_PROCESO -&gt; SALIDA_PUERTA
 *        |               |            |             |
 *        +---------------+------------+-------------+--&gt; CANCELADO
 * </pre>
 *
 * <p>{@code SALIDA_PUERTA} and {@code CANCELADO} are final states.
 */
public final class VisitStateMachine {

    private static final Map<VisitState, Set<VisitState>> ALLOWED = Map.of(
            VisitState.ENTRADA_PUERTA, EnumSet.of(VisitState.VALIDADO, VisitState.CANCELADO),
            VisitState.VALIDADO, EnumSet.of(VisitState.ASIGNADO, VisitState.CANCELADO),
            VisitState.ASIGNADO, EnumSet.of(VisitState.EN_PROCESO, VisitState.CANCELADO),
            VisitState.EN_PROCESO, EnumSet.of(VisitState.SALIDA_PUERTA, VisitState.CANCELADO),
            VisitState.SALIDA_PUERTA, EnumSet.noneOf(VisitState.class),
            VisitState.CANCELADO, EnumSet.noneOf(VisitState.class));

    private VisitStateMachine() {
        // utility class
    }

    /**
     * @param from current state
     * @param to   target state
     * @return true when the transition is allowed
     */
    public static boolean canTransition(VisitState from, VisitState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * @param from current state
     * @return the states reachable from the given one
     */
    public static Set<VisitState> allowedTargets(VisitState from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    /**
     * Verifies a transition and fails with a conflict (HTTP 409) when it is not allowed.
     *
     * @param from current state
     * @param to   target state
     * @throws ConflictException when the transition is not allowed
     */
    public static void assertTransition(VisitState from, VisitState to) {
        if (!canTransition(from, to)) {
            throw new ConflictException(
                    "Transición de estado no permitida: no se puede pasar de %s a %s.".formatted(from, to));
        }
    }
}
