package cr.logisticaportuaria.optimizer.congestion.domain;

/**
 * Metrics of a regression model, measured on a temporal hold-out set.
 *
 * @param mae       mean absolute error (minutes)
 * @param rmse      root mean squared error (minutes)
 * @param mape      mean absolute percentage error, in percent (rows with zero actual are skipped)
 * @param coverage80 share of rows whose actual value fell inside the predicted p10-p90 band
 * @param samples   number of evaluated rows
 */
public record ModelMetrics(double mae, double rmse, double mape, double coverage80, int samples) {

    /**
     * Computes the metrics.
     *
     * @param actual    observed values
     * @param predicted point forecasts
     * @param lower     p10 of each forecast
     * @param upper     p90 of each forecast
     * @return the metrics
     */
    public static ModelMetrics of(double[] actual, double[] predicted, double[] lower, double[] upper) {
        if (actual.length != predicted.length || actual.length != lower.length || actual.length != upper.length) {
            throw new IllegalArgumentException("all the arrays must have the same length");
        }
        if (actual.length == 0) {
            return new ModelMetrics(0.0, 0.0, 0.0, 0.0, 0);
        }
        double absolute = 0.0;
        double squared = 0.0;
        double percentage = 0.0;
        int percentageRows = 0;
        int covered = 0;
        for (int i = 0; i < actual.length; i++) {
            double error = actual[i] - predicted[i];
            absolute += Math.abs(error);
            squared += error * error;
            if (Math.abs(actual[i]) > 1e-9) {
                percentage += Math.abs(error) / Math.abs(actual[i]);
                percentageRows++;
            }
            if (actual[i] >= lower[i] && actual[i] <= upper[i]) {
                covered++;
            }
        }
        int n = actual.length;
        return new ModelMetrics(
                absolute / n,
                Math.sqrt(squared / n),
                percentageRows == 0 ? 0.0 : 100.0 * percentage / percentageRows,
                (double) covered / n,
                n);
    }
}
