package cr.logisticaportuaria.optimizer.congestion.support;

import static org.assertj.core.api.Assertions.assertThat;

import cr.logisticaportuaria.optimizer.congestion.domain.CostaRicaCalendar;
import cr.logisticaportuaria.optimizer.congestion.domain.GateHistory;
import cr.logisticaportuaria.optimizer.congestion.domain.HistoricalVisit;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Checks of the discrete-event simulator used to generate the synthetic history. */
class CongestionScenarioSimulatorTest {

    private static final UUID GATE = UUID.fromString("33333333-3333-7333-8333-333333333331");
    private static final UUID YARD = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final Instant START = LocalDate.of(2026, 9, 1)
            .atStartOfDay(CostaRicaCalendar.ZONE).toInstant();

    @Test
    @DisplayName("the same seed produces the same history")
    void sameSeedProducesTheSameHistory() {
        var parameters = CongestionScenarioSimulator.Parameters.reference();

        var first = CongestionScenarioSimulator.simulate(GATE, YARD, "G1", START, 3, parameters);
        var second = CongestionScenarioSimulator.simulate(GATE, YARD, "G1", START, 3, parameters);

        assertThat(first.visits()).hasSize(second.visits().size());
        assertThat(first.visits().get(0).enteredAt()).isEqualTo(second.visits().get(0).enteredAt());
        assertThat(first.visits()).isEqualTo(second.visits());
    }

    @Test
    @DisplayName("the peaks generate more arrivals than the night")
    void peaksGenerateMoreArrivalsThanTheNight() {
        var simulation = CongestionScenarioSimulator.simulate(GATE, YARD, "G1", START, 7,
                CongestionScenarioSimulator.Parameters.reference());

        long morning = simulation.visits().stream()
                .filter(visit -> hour(visit) >= 7 && hour(visit) < 10)
                .count();
        long night = simulation.visits().stream()
                .filter(visit -> hour(visit) >= 22 || hour(visit) < 6)
                .count();

        assertThat(simulation.visits()).isNotEmpty();
        assertThat(morning).isGreaterThan(night);
        assertThat(simulation.history().lanes()).isEqualTo(3);
        assertThat(simulation.history().appointmentTimes()).isNotEmpty();
        // 60 % of the trucks have an appointment
        assertThat(simulation.history().appointmentTimes().size())
                .isBetween((int) (simulation.visits().size() * 0.5), (int) (simulation.visits().size() * 0.7));
    }

    @Test
    @DisplayName("the service times and the waits are consistent with the visit window")
    void visitsAreConsistent() {
        var simulation = CongestionScenarioSimulator.simulate(GATE, YARD, "G1", START, 2,
                CongestionScenarioSimulator.Parameters.reference());

        for (HistoricalVisit visit : simulation.visits()) {
            assertThat(visit.exitedAt()).isAfter(visit.enteredAt());
            assertThat(visit.waitMinutes()).isGreaterThanOrEqualTo(0.0);
            assertThat(visit.serviceMinutes()).isPositive();
            double visitMinutes = Duration.between(visit.enteredAt(), visit.exitedAt()).toSeconds() / 60.0;
            assertThat(visit.waitMinutes() + visit.serviceMinutes()).isCloseTo(visitMinutes,
                    org.assertj.core.data.Offset.offset(0.05));
        }
        GateHistory history = simulation.history();
        assertThat(history.visits()).isSortedAccordingTo((left, right) ->
                left.enteredAt().compareTo(right.enteredAt()));
        assertThat(history.openVisits()).isZero();
        List<HistoricalVisit> visits = history.visits();
        assertThat(visits).isNotEmpty();
    }

    @Test
    @DisplayName("the arrival rate follows the daily profile")
    void arrivalRateFollowsTheDailyProfile() {
        double base = 12.0;
        double morning = CongestionScenarioSimulator.ratePerHour(START.plus(8, java.time.temporal.ChronoUnit.HOURS), base);
        double midday = CongestionScenarioSimulator.ratePerHour(START.plus(12, java.time.temporal.ChronoUnit.HOURS), base);
        double night = CongestionScenarioSimulator.ratePerHour(START.plus(2, java.time.temporal.ChronoUnit.HOURS), base);

        assertThat(morning).isGreaterThan(midday);
        assertThat(midday).isGreaterThan(night);
        assertThat(morning / base).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(night / base).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
    }

    private static int hour(HistoricalVisit visit) {
        return visit.enteredAt().atZone(CostaRicaCalendar.ZONE).getHour();
    }
}
