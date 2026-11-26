package cr.logisticaportuaria.optimizer.congestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Checks of the Holt-Winters smoothing used to project the arrivals. */
class ExponentialSmoothingTest {

    @Test
    @DisplayName("a constant series is forecast as a constant")
    void constantSeriesIsForecastAsConstant() {
        double[] series = new double[200];
        java.util.Arrays.fill(series, 10.0);
        ExponentialSmoothing model = new ExponentialSmoothing(4);
        model.fit(series);

        double[] forecast = model.forecast(8);

        assertThat(forecast).hasSize(8);
        for (double value : forecast) {
            assertThat(value).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.01));
        }
    }

    @Test
    @DisplayName("a periodic series keeps its seasonal shape")
    void periodicSeriesKeepsItsSeason() {
        double[] pattern = {1.0, 2.0, 3.0, 4.0};
        double[] series = new double[200];
        for (int i = 0; i < series.length; i++) {
            series[i] = pattern[i % pattern.length];
        }
        ExponentialSmoothing model = new ExponentialSmoothing(4);
        model.fit(series);

        double[] forecast = model.forecast(4);

        for (int i = 0; i < 4; i++) {
            assertThat(forecast[i]).isCloseTo(pattern[i], org.assertj.core.data.Offset.offset(0.5));
        }
    }

    @Test
    @DisplayName("seasonal naive repeats the same bucket of the previous season")
    void seasonalNaiveRepeatsThePreviousSeason() {
        double[] series = {1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0};

        double[] forecast = ExponentialSmoothing.seasonalNaive(series, 4, 4);

        assertThat(forecast).containsExactly(10.0, 20.0, 30.0, 40.0);
    }

    @Test
    @DisplayName("two seasons are required to fit")
    void twoSeasonsAreRequired() {
        ExponentialSmoothing model = new ExponentialSmoothing(4);

        assertThatThrownBy(() -> model.fit(new double[] {1.0, 2.0, 3.0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model.forecast(2))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the walk-forward forecast of a constant series is the constant")
    void walkForwardOfAConstantSeries() {
        double[] series = new double[8 * 96];
        java.util.Arrays.fill(series, 4.0);
        double[][] forecasts = ExponentialSmoothing.walkForward(series, 96, 4);

        assertThat(forecasts).hasNumberOfRows(5);
        // warmed up from the third season on
        assertThat(forecasts[1][2 * 96 - 1]).isNaN();
        assertThat(forecasts[1][2 * 96]).isCloseTo(4.0, org.assertj.core.data.Offset.offset(0.01));
        // lead 4 needs the origin of bucket 192, which is 188: still warming up
        assertThat(forecasts[4][192]).isNaN();
        assertThat(forecasts[4][4 + 2 * 96 - 1]).isCloseTo(4.0, org.assertj.core.data.Offset.offset(0.02));
    }

    @Test
    @DisplayName("the walk-forward forecast never uses later observations")
    void walkForwardDoesNotLeakTheFuture() {
        double[] series = new double[6 * 96];
        for (int i = 0; i < series.length; i++) {
            series[i] = 2.0 + Math.sin(2 * Math.PI * i / 96.0);
        }
        double[] other = series.clone();
        // change everything after bucket 500: the forecasts up to 500 must not move
        for (int i = 501; i < other.length; i++) {
            other[i] = 50.0;
        }
        double[][] original = ExponentialSmoothing.walkForward(series, 96, 4);
        double[][] modified = ExponentialSmoothing.walkForward(other, 96, 4);
        for (int lead = 1; lead <= 4; lead++) {
            for (int bucket = 0; bucket <= 500; bucket++) {
                if (!Double.isNaN(original[lead][bucket])) {
                    assertThat(modified[lead][bucket]).isEqualTo(original[lead][bucket]);
                }
            }
        }
        assertThat(modified[1][520]).isNotEqualTo(original[1][520]);
    }
}
