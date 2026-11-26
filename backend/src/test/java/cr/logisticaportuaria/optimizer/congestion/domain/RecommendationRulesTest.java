package cr.logisticaportuaria.optimizer.congestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test of the actionable recommendations.
 */
class RecommendationRulesTest {

    /** Thursday 2026-09-17 17:45 local (23:45Z), inside the afternoon rush window. */
    private static final Instant PEAK = Instant.parse("2026-09-17T23:45:00Z");

    @Test
    @DisplayName("riesgo bajo con holgura devuelve una sola recomendación de operación normal")
    void lowRiskWithSpareCapacityIsNormalOperation() {
        List<String> suggestions = RecommendationRules.suggest(RiskLevel.BAJO, 3, 0.55, 2, PEAK, 8.0, false);
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0)).contains("Operación normal").contains("3 carriles");
    }

    @Test
    @DisplayName("riesgo bajo pero demanda por encima de la capacidad avisa del refuerzo")
    void lowRiskWithOverloadWarns() {
        List<String> suggestions = RecommendationRules.suggest(RiskLevel.BAJO, 3, 1.11, 9, PEAK, 12.0, false);
        assertThat(suggestions).hasSize(2);
        assertThat(String.join(" ", suggestions)).contains("supera la atención disponible")
                .contains("111 %");
    }

    @Test
    @DisplayName("riesgo alto abre un carril y avisa a los transportistas")
    void highRiskOpensALane() {
        List<String> suggestions = RecommendationRules.suggest(RiskLevel.ALTO, 3, 1.4, 12, PEAK, 45.0, false);
        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.get(0)).contains("Abrir al menos un carril").contains("17:45");
        assertThat(suggestions.get(1)).contains("Escalonar las citas");
    }

    @Test
    @DisplayName("riesgo medio suma el aviso de feriado y el carril de reserva de la cola alta")
    void mediumRiskAddsTheHolidayAndTheReserveLane() {
        List<String> suggestions = RecommendationRules.suggest(RiskLevel.MEDIO, 2, 0.9, 4, PEAK, 38.0, true);
        String text = String.join(" ", suggestions);
        assertThat(text).contains("Reforzar la puerta").contains("Feriado nacional")
                .contains("carril de reserva");
    }

    @Test
    @DisplayName("la franja de punta se nombra en español")
    void rushWindowLabels() {
        assertThat(RecommendationRules.rushWindow(PEAK)).contains("punta de la tarde");
        assertThat(RecommendationRules.rushWindow(Instant.parse("2026-09-17T12:00:00Z"))).isEmpty();
        assertThat(RecommendationRules.isRushHour(PEAK)).isTrue();
    }
}
