package cr.logisticaportuaria.optimizer.congestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Models trained for one gate and cached by the service.
 *
 * <p>Holds one {@link HorizonModel} per horizon, the 15-minute arrival series with its
 * exponential smoothing (used to project the arrivals of the horizon), the measured training
 * time and the global confidence, so the API can explain how good the forecast is.
 */
public final class TrainedCongestionModel {

    private final UUID gateId;
    private final String gateCode;
    private final int lanes;
    private final double serviceMinutes;
    private final double serviceCv2;
    private final double[] arrivals;
    private final Instant origin;
    private final ExponentialSmoothing smoother;
    private final Map<Integer, HorizonModel> horizons;
    private final int samples;
    private final ConfidenceLevel confidence;
    private final Instant trainedAt;
    private final long trainingMillis;

    /**
     * Constructor.
     *
     * @param gateId         identifier of the gate
     * @param gateCode       human readable code of the gate
     * @param lanes          number of lanes
     * @param serviceMinutes mean service time per lane
     * @param serviceCv2     squared coefficient of variation of the service time
     * @param arrivals       first arrival of the series
     * @param origin         start of the arrival series
     * @param smoother       fitted smoothing of the arrivals ({@code null} when there is not
     *                       enough history)
     * @param horizons       model of each horizon
     * @param samples        number of buckets used to fit the models
     * @param confidence     global confidence of the forecast
     * @param trainedAt      instant of the training
     * @param trainingMillis measured training time
     */
    public TrainedCongestionModel(UUID gateId, String gateCode, int lanes, double serviceMinutes, double serviceCv2,
            double[] arrivals, Instant origin, ExponentialSmoothing smoother, Map<Integer, HorizonModel> horizons,
            int samples, ConfidenceLevel confidence, Instant trainedAt, long trainingMillis) {
        this.gateId = gateId;
        this.gateCode = gateCode;
        this.lanes = lanes;
        this.serviceMinutes = serviceMinutes;
        this.serviceCv2 = serviceCv2;
        this.arrivals = arrivals.clone();
        this.origin = origin;
        this.smoother = smoother;
        this.horizons = Map.copyOf(horizons);
        this.samples = samples;
        this.confidence = confidence;
        this.trainedAt = trainedAt;
        this.trainingMillis = trainingMillis;
    }

    public UUID gateId() {
        return gateId;
    }

    public String gateCode() {
        return gateCode;
    }

    public int lanes() {
        return lanes;
    }

    public double serviceMinutes() {
        return serviceMinutes;
    }

    public double serviceCv2() {
        return serviceCv2;
    }

    public double[] arrivals() {
        return arrivals.clone();
    }

    public Instant origin() {
        return origin;
    }

    public ExponentialSmoothing smoother() {
        return smoother;
    }

    /** @return the horizons with a fitted model, in ascending order */
    public Set<Integer> horizons() {
        return horizons.keySet();
    }

    /**
     * Model of one horizon.
     *
     * @param horizonMin horizon in minutes
     * @return the model
     */
    public HorizonModel horizon(int horizonMin) {
        HorizonModel model = horizons.get(horizonMin);
        if (model == null) {
            throw new IllegalArgumentException("No hay modelo para el horizonte " + horizonMin + " min");
        }
        return model;
    }

    public int samples() {
        return samples;
    }

    public ConfidenceLevel confidence() {
        return confidence;
    }

    public Instant trainedAt() {
        return trainedAt;
    }

    public long trainingMillis() {
        return trainingMillis;
    }

    /**
     * @return the backtest rows of the 15-minute horizon, oldest first
     */
    public List<BacktestRow> backtest() {
        return horizon(15).backtest();
    }
}
