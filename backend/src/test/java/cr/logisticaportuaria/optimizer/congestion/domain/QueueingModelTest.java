package cr.logisticaportuaria.optimizer.congestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed checks of the queueing model.
 *
 * <p>Every expected value is derived by hand in the test so a change in the formula cannot
 * pass unnoticed.
 */
class QueueingModelTest {

    private static final double TOLERANCE = 1e-6;

    @Test
    @DisplayName("M/M/1: Wq = rho / (mu - lambda) and Lq = rho^2 / (1 - rho)")
    void mmcSingleServerMatchesTheTextbookFormula() {
        // lambda = 12/h = 0.2/min, service = 4 min -> mu = 0.25/min, rho = 0.8
        QueueingResult result = QueueingModel.mmc(12.0, 4.0, 1);

        assertThat(result.stable()).isTrue();
        assertThat(result.utilization()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(result.waitMinutes()).isCloseTo(16.0, org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(result.queueLength()).isCloseTo(3.2, org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(result.probabilityOfWait()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(TOLERANCE));
    }

    @Test
    @DisplayName("M/M/2: Erlang C gives C = 0.7111 and Wq = 14.2222 min")
    void mmcTwoServersMatchesErlangC() {
        // lambda = 0.2/min, service = 8 min -> mu = 0.125, c = 2, a = 1.6, rho = 0.8
        // sum(k=0..1) a^k/k! = 1 + 1.6 = 2.6; a^c/c! = 1.28; tail = 1.28 / 0.2 = 6.4
        // C = 6.4 / (2.6 + 6.4) = 0.711111; Wq = C / (c*mu - lambda) = 0.711111 / 0.05
        QueueingResult result = QueueingModel.mmc(12.0, 8.0, 2);

        assertThat(result.probabilityOfWait()).isCloseTo(6.4 / 9.0, org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(result.waitMinutes()).isCloseTo(14.222222, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(result.queueLength()).isCloseTo(2.844444, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    @DisplayName("Allen-Cunneen scales the wait and the queue by (ca2 + cs2) / 2")
    void allenCunneenScalesTheMmCResult() {
        QueueingResult poisson = QueueingModel.mmc(12.0, 4.0, 1);
        // service with cs2 = 2 -> factor (1 + 2) / 2 = 1.5
        QueueingResult variable = QueueingModel.mgcAllenCunneen(12.0, 4.0, 1, 1.0, 2.0);

        assertThat(variable.waitMinutes()).isCloseTo(24.0, org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(variable.queueLength()).isCloseTo(4.8, org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(variable.waitMinutes()).isCloseTo(poisson.waitMinutes() * 1.5,
                org.assertj.core.data.Offset.offset(TOLERANCE));
    }

    @Test
    @DisplayName("an overloaded gate is reported as unstable with the capped wait")
    void overloadedGateIsUnstable() {
        // lambda = 0.2/min, service = 6 min -> mu = 0.1667/min, rho = 1.2
        QueueingResult result = QueueingModel.mmc(12.0, 6.0, 1);

        assertThat(result.stable()).isFalse();
        assertThat(result.waitMinutes()).isEqualTo(QueueingModel.UNSTABLE_WAIT_MINUTES);
        assertThat(result.queueLength()).isEqualTo(QueueingModel.UNSTABLE_QUEUE);
    }

    @Test
    @DisplayName("no arrivals means no wait")
    void noArrivalsMeansNoWait() {
        QueueingResult result = QueueingModel.mmc(0.0, 8.0, 3);

        assertThat(result.waitMinutes()).isZero();
        assertThat(result.queueLength()).isZero();
        assertThat(result.stable()).isTrue();
    }

    @Test
    @DisplayName("the p10-p90 band follows the exponential tail of M/M/c")
    void quantilesFollowTheExponentialTail() {
        QueueingResult result = QueueingModel.mmc(12.0, 4.0, 1);
        // P(W > t) = 0.8 * exp(-0.05 t) -> p90 = ln(8) / 0.05 = 41.5888 min
        assertThat(QueueingModel.waitQuantile(result, 0.90)).isCloseTo(41.588830,
                org.assertj.core.data.Offset.offset(1e-5));
        // 1 - 0.9 = 0.1 is below P(W > 0) = 0.8 -> the p10 wait is zero
        assertThat(QueueingModel.waitQuantile(result, 0.10)).isZero();
        // the quantiles of an unstable model stay at the cap
        assertThat(QueueingModel.waitQuantile(QueueingModel.mmc(12.0, 6.0, 1), 0.9))
                .isEqualTo(QueueingModel.UNSTABLE_WAIT_MINUTES);
    }

    @Test
    @DisplayName("the horizon wait adds the backlog that does not drain in time")
    void horizonWaitAddsTheBacklog() {
        // c = 2, service = 8 min -> capacity = 0.25/min = 15/h; lambda = 12/h -> no backlog
        double withoutBacklog = QueueingModel.waitAtHorizon(0, 12.0, 8.0, 2, 60.0);
        assertThat(withoutBacklog).isCloseTo(14.222222, org.assertj.core.data.Offset.offset(1e-5));

        // 5 trucks waiting -> 5 + 12 - 15 = 2 remain after 60 min -> 2 / 0.25 = 8 min extra
        double withBacklog = QueueingModel.waitAtHorizon(5, 12.0, 8.0, 2, 60.0);
        assertThat(withBacklog).isCloseTo(22.222222, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    @DisplayName("a saturated gate returns only the backlog that cannot be drained")
    void saturatedHorizonReturnsTheBacklogOnly() {
        // 36 arrivals/h against 25.71/h of capacity (3 lanes, 7 min): rho = 1.4, unstable
        // backlog after 15 min = 36 * 0.25 - 25.71 * 0.25 = 2.57 trucks -> 2.57 / 0.4286 = 6 min
        double wait = QueueingModel.waitAtHorizon(0, 36.0, 7.0, 3, 15.0);
        assertThat(wait).isCloseTo(6.0, org.assertj.core.data.Offset.offset(0.01));

        // the trucks already waiting are added to the backlog
        double withQueue = QueueingModel.waitAtHorizon(4, 36.0, 7.0, 3, 15.0);
        assertThat(withQueue).isCloseTo(15.33, org.assertj.core.data.Offset.offset(0.02));
        // and the result never exceeds the cap of the model
        assertThat(QueueingModel.waitAtHorizon(500, 36.0, 7.0, 3, 15.0))
                .isEqualTo(QueueingModel.UNSTABLE_WAIT_MINUTES);
    }

    @Test
    @DisplayName("invalid arguments are rejected")
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> QueueingModel.mmc(10.0, 5.0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueingModel.waitQuantile(QueueingModel.mmc(10.0, 5.0, 1), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
