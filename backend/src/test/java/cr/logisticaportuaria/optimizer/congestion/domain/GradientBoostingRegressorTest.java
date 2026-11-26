package cr.logisticaportuaria.optimizer.congestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Checks of the self-contained gradient boosting regressor. */
class GradientBoostingRegressorTest {

    @Test
    @DisplayName("it learns a linear relation between one feature and the target")
    void learnsALinearRelation() {
        Random random = new Random(7L);
        double[][] features = new double[200][1];
        double[] target = new double[200];
        for (int i = 0; i < 200; i++) {
            double x = random.nextDouble();
            features[i][0] = x;
            target[i] = 2.0 + 5.0 * x;
        }
        GradientBoostingRegressor model = new GradientBoostingRegressor();
        model.fit(features, target);

        double error = 0.0;
        for (int i = 0; i < 200; i++) {
            error += Math.abs(target[i] - model.predict(features[i]));
        }
        assertThat(error / 200.0).isLessThan(0.5);
        assertThat(model.predict(new double[] {0.0})).isBetween(1.5, 2.6);
        assertThat(model.predict(new double[] {1.0})).isBetween(6.4, 7.5);
    }

    @Test
    @DisplayName("the fit is deterministic for the same data")
    void fitIsDeterministic() {
        Random random = new Random(11L);
        double[][] features = new double[150][3];
        double[] target = new double[150];
        for (int i = 0; i < 150; i++) {
            features[i][0] = random.nextDouble();
            features[i][1] = random.nextDouble() * 24.0;
            features[i][2] = random.nextBoolean() ? 1.0 : 0.0;
            target[i] = features[i][0] * 10.0 + Math.sin(features[i][1]) + features[i][2] * 3.0;
        }
        GradientBoostingRegressor first = new GradientBoostingRegressor();
        GradientBoostingRegressor second = new GradientBoostingRegressor();
        first.fit(features, target);
        second.fit(features, target);

        for (int i = 0; i < 150; i++) {
            assertThat(first.predict(features[i])).isEqualTo(second.predict(features[i]));
        }
        assertThat(first.size()).isEqualTo(60);
    }

    @Test
    @DisplayName("predicting before fitting is an error")
    void predictingBeforeFittingIsAnError() {
        GradientBoostingRegressor model = new GradientBoostingRegressor();

        assertThatThrownBy(() -> model.predict(new double[] {1.0}))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> model.fit(new double[][] {{1.0}}, new double[] {1.0, 2.0}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
