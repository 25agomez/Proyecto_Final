package cr.logisticaportuaria.optimizer.congestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Checks of the Costa Rican calendar used by the model. */
class CostaRicaCalendarTest {

    @Test
    @DisplayName("Easter Sunday of 2026 is April 5th")
    void easterSundayIsComputed() {
        assertThat(CostaRicaCalendar.easterSunday(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(CostaRicaCalendar.easterSunday(2025)).isEqualTo(LocalDate.of(2025, 4, 20));
        assertThat(CostaRicaCalendar.easterSunday(2024)).isEqualTo(LocalDate.of(2024, 3, 31));
    }

    @Test
    @DisplayName("Holy Week and the fixed holidays are flagged")
    void holidaysAreFlagged() {
        assertThat(CostaRicaCalendar.isHoliday(LocalDate.of(2026, 4, 2))).isTrue();  // Jueves Santo
        assertThat(CostaRicaCalendar.isHoliday(LocalDate.of(2026, 4, 3))).isTrue();  // Viernes Santo
        assertThat(CostaRicaCalendar.isHoliday(LocalDate.of(2026, 9, 15))).isTrue(); // Independencia
        assertThat(CostaRicaCalendar.isHoliday(LocalDate.of(2026, 12, 25))).isTrue();
        assertThat(CostaRicaCalendar.isHoliday(LocalDate.of(2026, 3, 18))).isFalse();
    }

    @Test
    @DisplayName("the local zone is Costa Rica without daylight saving time")
    void localZoneIsCostaRica() {
        assertThat(CostaRicaCalendar.ZONE.getId()).isEqualTo("America/Costa_Rica");
        // UTC-6 the whole year: no daylight saving time since 1992
        assertThat(CostaRicaCalendar.ZONE.getRules().getOffset(java.time.Instant.parse("2026-01-15T12:00:00Z")))
                .isEqualTo(java.time.ZoneOffset.ofHours(-6));
        assertThat(CostaRicaCalendar.ZONE.getRules().getOffset(java.time.Instant.parse("2026-07-15T12:00:00Z")))
                .isEqualTo(java.time.ZoneOffset.ofHours(-6));
    }
}
