package cr.logisticaportuaria.optimizer.congestion.domain;

/**
 * Holt-Winters additive exponential smoothing for a seasonal series of 15-minute buckets.
 *
 * <p>Used to project the number of arrivals of each gate. The seasonal component has one
 * position per bucket of the day (96 by default) and the model is refitted from scratch
 * on every training run, which keeps it deterministic for a fixed history.
 */
public final class ExponentialSmoothing {

    private final int seasonLength;
    private final double alpha;
    private final double beta;
    private final double gamma;

    private double level;
    private double trend;
    private double[] seasonal = new double[0];
    private int length;
    private double lastError;
    private boolean fitted;

    /**
     * Creates a model with the default smoothing parameters.
     *
     * @param seasonLength buckets per season (96 for a 15-minute bucket and a daily season)
     */
    public ExponentialSmoothing(int seasonLength) {
        this(seasonLength, 0.3, 0.05, 0.3);
    }

    /**
     * Creates a model.
     *
     * @param seasonLength buckets per season
     * @param alpha        level smoothing
     * @param beta         trend smoothing
     * @param gamma        seasonal smoothing
     */
    public ExponentialSmoothing(int seasonLength, double alpha, double beta, double gamma) {
        if (seasonLength < 2) {
            throw new IllegalArgumentException("seasonLength must be at least 2");
        }
        requireProbability(alpha, "alpha");
        requireProbability(beta, "beta");
        requireProbability(gamma, "gamma");
        this.seasonLength = seasonLength;
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
    }

    private static void requireProbability(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0, 1]");
        }
    }

    /**
     * Fits the model.
     *
     * @param series the observed buckets, oldest first
     */
    public void fit(double[] series) {
        if (series.length < 2 * seasonLength) {
            throw new IllegalArgumentException("at least two seasons are required");
        }
        double first = mean(series, 0, seasonLength);
        double second = mean(series, seasonLength, seasonLength);
        level = first;
        trend = (second - first) / seasonLength;
        seasonal = new double[seasonLength];
        for (int i = 0; i < seasonLength; i++) {
            seasonal[i] = series[i] - first;
        }
        for (int t = 0; t < series.length; t++) {
            int index = t % seasonLength;
            double season = seasonal[index];
            double forecast = level + trend + season;
            double error = series[t] - forecast;
            double newLevel = alpha * (series[t] - season) + (1.0 - alpha) * (level + trend);
            double newTrend = beta * (newLevel - level) + (1.0 - beta) * trend;
            seasonal[index] = gamma * (series[t] - newLevel) + (1.0 - gamma) * season;
            level = newLevel;
            trend = newTrend;
            if (t == series.length - 1) {
                // keep the last in-sample error available for diagnostics
                lastError = error;
            }
        }
        length = series.length;
        fitted = true;
    }

    /**
     * Forecasts the next buckets.
     *
     * @param horizon number of buckets to forecast
     * @return the forecast, oldest first; never negative
     */
    public double[] forecast(int horizon) {
        if (!fitted) {
            throw new IllegalStateException("the model has not been fitted");
        }
        if (horizon <= 0) {
            throw new IllegalArgumentException("horizon must be positive");
        }
        double[] out = new double[horizon];
        for (int h = 1; h <= horizon; h++) {
            double season = seasonal[(length + h - 1) % seasonLength];
            out[h - 1] = Math.max(0.0, level + trend * h + season);
        }
        return out;
    }

    /**
     * @return the last in-sample error (observed minus forecast)
     */
    public double lastError() {
        return lastError;
    }

    /**
     * Walk-forward forecasts of a series: for every bucket, the forecast made {@code lead}
     * buckets before with only the observations available at that moment.
     *
     * <p>The smoothing parameters are fixed a priori (never optimized on the series), so the
     * forecast of a bucket does not contain any later observation. This is the estimator of
     * the statistical baseline of the congestion experiment.
     *
     * @param series       observed buckets, oldest first
     * @param seasonLength buckets per season
     * @param maxLead      largest lead to record, in buckets
     * @return {@code [lead][bucket]} forecasts; {@code NaN} while the model is warming up or
     *         when the target bucket does not exist
     */
    public static double[][] walkForward(double[] series, int seasonLength, int maxLead) {
        if (series.length < 2 * seasonLength) {
            return new double[0][];
        }
        if (maxLead < 1) {
            throw new IllegalArgumentException("maxLead must be positive");
        }
        double alpha = 0.3;
        double beta = 0.05;
        double gamma = 0.3;
        double[][] out = new double[maxLead + 1][series.length];
        for (double[] row : out) {
            java.util.Arrays.fill(row, Double.NaN);
        }
        double first = mean(series, 0, seasonLength);
        double second = mean(series, seasonLength, seasonLength);
        double level = first;
        double trend = (second - first) / seasonLength;
        double[] seasonal = new double[seasonLength];
        for (int i = 0; i < seasonLength; i++) {
            seasonal[i] = series[i] - first;
        }
        // the first two seasons only warm the model up: no forecast is recorded from them
        for (int t = 0; t < series.length; t++) {
            if (t >= 2 * seasonLength - 1) {
                for (int lead = 1; lead <= maxLead && t + lead < series.length; lead++) {
                    int position = (t + lead) % seasonLength;
                    out[lead][t + lead] = Math.max(0.0, level + trend * lead + seasonal[position]);
                }
            }
            int index = t % seasonLength;
            double season = seasonal[index];
            double newLevel = alpha * (series[t] - season) + (1.0 - alpha) * (level + trend);
            double newTrend = beta * (newLevel - level) + (1.0 - beta) * trend;
            seasonal[index] = gamma * (series[t] - newLevel) + (1.0 - gamma) * season;
            level = newLevel;
            trend = newTrend;
        }
        return out;
    }

    /**
     * Seasonal naive forecast: the value of the same bucket of the previous season.
     *
     * @param series       observed buckets, oldest first
     * @param seasonLength buckets per season
     * @param horizon      number of buckets to forecast
     * @return the forecast
     */
    public static double[] seasonalNaive(double[] series, int seasonLength, int horizon) {
        if (series.length < seasonLength) {
            throw new IllegalArgumentException("the series must hold at least one season");
        }
        double[] out = new double[horizon];
        for (int h = 1; h <= horizon; h++) {
            int index = series.length - seasonLength + ((h - 1) % seasonLength);
            out[h - 1] = Math.max(0.0, series[index]);
        }
        return out;
    }

    private static double mean(double[] values, int from, int count) {
        double sum = 0.0;
        for (int i = from; i < from + count; i++) {
            sum += values[i];
        }
        return sum / count;
    }
}
