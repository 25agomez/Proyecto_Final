package cr.logisticaportuaria.optimizer.congestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Hybrid congestion forecaster: queueing theory plus a gradient boosting correction.
 *
 * <p>The forecaster aggregates the history of a gate in 15-minute buckets and, for each
 * horizon of the prompt (15, 30 and 60 minutes), fits the average wait experienced by the
 * trucks that arrive in a bucket. Every horizon has its own model, evaluated on a temporal
 * hold-out (the last 20 % of the buckets) against two baselines:
 *
 * <ol>
 *   <li>{@code COLAS}: M/G/c Allen-Cunneen with the waiting queue observed at the origin of
 *       the forecast and the seasonal arrival rate of the target slot of the week, extended
 *       with {@link QueueingModel#waitAtHorizon}.</li>
 *   <li>{@code SUAVIZADO}: the same queueing model but with the arrival rate forecast by
 *       Holt-Winters ({@link ExponentialSmoothing#walkForward}) instead of the seasonal
 *       profile; it is the statistical approach of the prompt, evaluated in wait space.</li>
 *   <li>{@code HIBRIDO}: the queueing baseline plus a gradient boosting correction of its
 *       residual. The features are the state at the origin (trucks waiting, arrivals of the
 *       previous hour) and the calendar of the target bucket, all of them known when the
 *       forecast is made: there is no leakage of the target bucket.</li>
 * </ol>
 *
 * <p>An extra baseline, the persistence of the wait observed in the previous hour, is measured
 * to prove the value of the model. The boosting correction is only used when it improves the
 * hold-out MAE of the queueing model, so a gate with weak history degrades to the theoretical
 * model instead of to an unvalidated ML output.
 *
 * <p>Two documented approximations keep the baseline stable: the arrival rate of a bucket is
 * the seasonal profile of the same day of the week and hour computed only with previous weeks
 * (a walk-forward estimator, never the future), and the queue of the model is the number of
 * trucks waiting for a lane, not the trucks already being served.
 */
public final class CongestionForecaster {

    /** Minutes per bucket. */
    public static final int BUCKET_MINUTES = 15;

    /** Buckets of a day. */
    public static final int BUCKETS_PER_DAY = 24 * 60 / BUCKET_MINUTES;

    /** Horizons of the prompt, in minutes. */
    public static final List<Integer> HORIZONS = List.of(15, 30, 60);

    private static final int BUCKETS_PER_HOUR = 60 / BUCKET_MINUTES;
    private static final int MAX_LEAD = 60 / BUCKET_MINUTES;
    private static final double MIN_SERVICE_MINUTES = 1.0;
    private static final double MAX_SERVICE_MINUTES = 120.0;
    private static final double DEFAULT_SERVICE_MINUTES = 12.0;
    private static final double DEFAULT_SERVICE_CV2 = 0.25;
    private static final int MIN_ROWS_FOR_MODEL = 40;
    private static final int MIN_ROWS_FOR_TRAIN = 20;
    private static final double CORRECTION_MARGIN = 0.05;
    private static final double MAX_WAIT_MINUTES = QueueingModel.UNSTABLE_WAIT_MINUTES;
    private static final double MAX_QUEUE = 500.0;
    private static final int MAX_BACKTEST_ROWS = 500;
    private static final Locale SPANISH = Locale.forLanguageTag("es-CR");
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final int seasonLength;
    private final int maxTrainingRows;
    private final double mediumRiskMinutes;
    private final double highRiskMinutes;

    /** Creates the forecaster with the default configuration. */
    public CongestionForecaster() {
        this(BUCKETS_PER_DAY, 2000, 15.0, 30.0);
    }

    /**
     * Creates the forecaster.
     *
     * @param seasonLength      buckets per season of the arrival smoothing (96 = one day)
     * @param maxTrainingRows   maximum number of buckets used to fit each model
     * @param mediumRiskMinutes wait (minutes) from which the risk is MEDIO
     * @param highRiskMinutes   wait (minutes) from which the risk is ALTO
     */
    public CongestionForecaster(int seasonLength, int maxTrainingRows, double mediumRiskMinutes,
            double highRiskMinutes) {
        if (seasonLength < 2 || maxTrainingRows < MIN_ROWS_FOR_MODEL) {
            throw new IllegalArgumentException("invalid forecaster configuration");
        }
        if (mediumRiskMinutes <= 0.0 || highRiskMinutes <= mediumRiskMinutes) {
            throw new IllegalArgumentException("invalid risk thresholds");
        }
        this.seasonLength = seasonLength;
        this.maxTrainingRows = maxTrainingRows;
        this.mediumRiskMinutes = mediumRiskMinutes;
        this.highRiskMinutes = highRiskMinutes;
    }

    private record Row(Instant bucketStart, CongestionFeatures features, double target, double queuePrediction,
            double smoothedPrediction, double naivePrediction) {
    }

    private record Trained(HorizonModel model, int rows) {
    }

    /**
     * Trains the model of one gate with a temporal hold-out per horizon.
     *
     * @param history   history of the gate
     * @param reference end of the observation window
     * @return the trained model, with its metrics and confidence
     */
    public TrainedCongestionModel train(GateHistory history, Instant reference) {
        long started = System.currentTimeMillis();
        List<HistoricalVisit> visits = history.visits();
        double serviceMinutes = meanServiceMinutes(visits);
        double serviceCv2 = serviceCv2(visits, serviceMinutes);
        Instant origin = align(reference.minus(28, ChronoUnit.DAYS));
        if (!visits.isEmpty()) {
            origin = align(visits.get(0).enteredAt());
        }
        int buckets = (int) (Duration.between(origin, align(reference)).toMinutes() / BUCKET_MINUTES) + 1;
        buckets = Math.max(1, Math.min(buckets, 32 * BUCKETS_PER_DAY));

        double[] arrivals = new double[buckets];
        double[] waitSum = new double[buckets];
        int[] waitCount = new int[buckets];
        double[] appointments = new double[buckets];
        for (HistoricalVisit visit : visits) {
            int index = bucketIndex(origin, visit.enteredAt());
            if (index >= 0 && index < buckets) {
                arrivals[index]++;
                waitSum[index] += visit.waitMinutes();
                waitCount[index]++;
            }
        }
        for (Instant appointment : history.appointmentTimes()) {
            int index = bucketIndex(origin, appointment);
            if (index >= 0 && index < buckets) {
                appointments[index]++;
            }
        }

        double[] meanWait = new double[buckets];
        double[] startSum = new double[buckets];
        int[] startCount = new int[buckets];
        for (int index = 0; index < buckets; index++) {
            meanWait[index] = waitCount[index] > 0 ? waitSum[index] / waitCount[index] : Double.NaN;
        }
        for (HistoricalVisit visit : visits) {
            Instant serviceStart = visit.enteredAt().plusSeconds(Math.round(visit.waitMinutes() * 60.0));
            int startIndex = bucketIndex(origin, serviceStart);
            if (startIndex >= 0 && startIndex < buckets) {
                startSum[startIndex] += visit.waitMinutes();
                startCount[startIndex]++;
            }
        }
        // mean wait observed in the previous hour, available at the start of each bucket
        double[] recentWait = new double[buckets];
        for (int index = 0; index < buckets; index++) {
            double sum = 0.0;
            int count = 0;
            for (int previous = Math.max(0, index - BUCKETS_PER_HOUR); previous < index; previous++) {
                sum += startSum[previous];
                count += startCount[previous];
            }
            recentWait[index] = count == 0 ? Double.NaN : sum / count;
        }
        int[] queue = waitingQueueLengths(visits, origin, buckets);
        double[] profile = seasonalProfile(origin, arrivals);
        double[][] smoothedArrivals = ExponentialSmoothing.walkForward(arrivals, BUCKETS_PER_DAY, MAX_LEAD);

        ExponentialSmoothing smoother = null;
        if (arrivals.length >= 2 * seasonLength) {
            smoother = new ExponentialSmoothing(seasonLength);
            smoother.fit(arrivals);
        }

        Map<Integer, HorizonModel> models = new LinkedHashMap<>();
        int samples = 0;
        double worstMae = 0.0;
        for (int horizon : HORIZONS) {
            Trained trained = trainHorizon(history, horizon, origin, buckets, appointments, queue,
                    meanWait, recentWait, profile, smoothedArrivals, serviceMinutes);
            models.put(horizon, trained.model());
            samples = Math.max(samples, trained.rows());
            worstMae = Math.max(worstMae, trained.model().activeMetrics().mae());
        }
        ConfidenceLevel confidence = confidence(samples, worstMae);
        long elapsed = Math.max(0L, System.currentTimeMillis() - started);
        return new TrainedCongestionModel(history.gateId(), history.gateCode(), history.lanes(), serviceMinutes,
                serviceCv2, arrivals, origin, smoother, models, samples, confidence, reference, elapsed);
    }

    /**
     * Seasonal profile of the arrivals: mean count per bucket of every day of the week and
     * hour, computed <strong>only with the previous weeks</strong> of the series.
     *
     * <p>Because a (day of the week, hour) slot only repeats every seven days, the value used
     * for a bucket never contains data of that bucket nor of the days between the origin and
     * the target of the forecast.
     *
     * @param origin   start of the series
     * @param arrivals arrivals per bucket
     * @return expected arrivals per hour for every bucket
     */
    private static double[] seasonalProfile(Instant origin, double[] arrivals) {
        double[] profile = new double[arrivals.length];
        double[][] sum = new double[7][24];
        int[][] count = new int[7][24];
        for (int index = 0; index < arrivals.length; index++) {
            ZonedDateTime local = origin.plus((long) index * BUCKET_MINUTES, ChronoUnit.MINUTES)
                    .atZone(CostaRicaCalendar.ZONE);
            int day = local.getDayOfWeek().getValue() - 1;
            int hour = local.getHour();
            profile[index] = count[day][hour] == 0
                    ? arrivalsPerHour(arrivals, index)
                    : sum[day][hour] / count[day][hour] * BUCKETS_PER_HOUR;
            sum[day][hour] += arrivals[index];
            count[day][hour]++;
        }
        return profile;
    }

    private Trained trainHorizon(GateHistory history, int horizonMin, Instant origin, int buckets,
            double[] appointments, int[] queue, double[] meanWait, double[] recentWait, double[] profile,
            double[][] smoothedArrivals, double serviceMinutes) {
        int lead = horizonMin / BUCKET_MINUTES;
        List<Row> rows = new ArrayList<>();
        for (int bucket = lead; bucket < buckets; bucket++) {
            if (Double.isNaN(meanWait[bucket])) {
                continue;
            }
            int source = bucket - lead;
            Instant target = origin.plus((long) bucket * BUCKET_MINUTES, ChronoUnit.MINUTES);
            double perHour = profile[bucket];
            double appointmentsNextHour = appointmentsWithin(appointments, bucket, BUCKETS_PER_HOUR);
            double baseline = QueueingModel.waitAtHorizon(queue[source], perHour, serviceMinutes, history.lanes(),
                    horizonMin);
            // statistical baseline: the arrival rate forecast with Holt-Winters instead of the
            // seasonal profile, translated to a wait with the same queueing model
            double smoothedPerHour = smoothedArrivals.length > lead && !Double.isNaN(smoothedArrivals[lead][bucket])
                    ? smoothedArrivals[lead][bucket] * BUCKETS_PER_HOUR
                    : perHour;
            double smoothed = QueueingModel.waitAtHorizon(queue[source], smoothedPerHour, serviceMinutes,
                    history.lanes(), horizonMin);
            double naive = Double.isNaN(recentWait[source]) ? baseline : recentWait[source];
            CongestionFeatures features = CongestionFeatures.at(target, CostaRicaCalendar.isHoliday(target),
                    queue[source], appointmentsNextHour, history.lanes(), perHour, serviceMinutes, baseline, naive);
            rows.add(new Row(target, features, meanWait[bucket], baseline, smoothed, naive));
        }
        return fit(horizonMin, rows);
    }

    private Trained fit(int horizonMin, List<Row> rows) {
        int totalRows = rows.size();
        List<Row> window = totalRows > maxTrainingRows
                ? rows.subList(totalRows - maxTrainingRows, totalRows)
                : rows;
        int samples = window.size();
        int split = (int) Math.floor(samples * 0.8);

        double[] queuePrediction = window.stream().mapToDouble(Row::queuePrediction).toArray();
        double[] smoothedPrediction = window.stream().mapToDouble(Row::smoothedPrediction).toArray();
        double[] naivePrediction = window.stream().mapToDouble(Row::naivePrediction).toArray();
        double[] actual = window.stream().mapToDouble(Row::target).toArray();

        GradientBoostingRegressor regressor = new GradientBoostingRegressor(120, 4, 16, 10, 0.1);
        boolean enoughRows = samples >= MIN_ROWS_FOR_MODEL && split >= MIN_ROWS_FOR_TRAIN
                && samples - split >= MIN_ROWS_FOR_TRAIN;
        double[] hybridPrediction = queuePrediction.clone();
        if (enoughRows) {
            double[][] features = window.stream()
                    .map(row -> row.features().toVector()).toArray(double[][]::new);
            double[] residual = new double[samples];
            for (int i = 0; i < samples; i++) {
                residual[i] = actual[i] - queuePrediction[i];
            }
            regressor.fit(Arrays.copyOfRange(features, 0, split), Arrays.copyOfRange(residual, 0, split));
            for (int i = split; i < samples; i++) {
                hybridPrediction[i] = clamp(queuePrediction[i] + regressor.predict(features[i]), 0.0,
                        MAX_WAIT_MINUTES);
            }
        }

        double[] queueBand = band(slice(queuePrediction, 0, split), slice(actual, 0, split));
        double[] hybridBand = enoughRows ? band(slice(hybridPrediction, 0, split), slice(actual, 0, split)) : queueBand;
        double[] naiveBand = band(slice(naivePrediction, 0, split), slice(actual, 0, split));
        double[] smoothedBand = band(slice(smoothedPrediction, 0, split), slice(actual, 0, split));

        ModelMetrics queueMetrics = evaluate(slice(actual, split, samples), slice(queuePrediction, split, samples),
                queueBand);
        ModelMetrics hybridMetrics = enoughRows
                ? evaluate(slice(actual, split, samples), slice(hybridPrediction, split, samples), hybridBand)
                : queueMetrics;
        ModelMetrics naiveMetrics = evaluate(slice(actual, split, samples), slice(naivePrediction, split, samples),
                naiveBand);
        ModelMetrics smoothedMetrics = evaluate(slice(actual, split, samples),
                slice(smoothedPrediction, split, samples), smoothedBand);

        boolean correctionUsed = enoughRows && hybridMetrics.mae() + CORRECTION_MARGIN < queueMetrics.mae();
        double[] activeBand = correctionUsed ? hybridBand : queueBand;
        double[] usedPrediction = correctionUsed ? hybridPrediction : queuePrediction;
        List<BacktestRow> backtest = new ArrayList<>();
        for (int i = split; i < samples && backtest.size() < MAX_BACKTEST_ROWS; i++) {
            backtest.add(new BacktestRow(window.get(i).bucketStart(), actual[i], queuePrediction[i],
                    smoothedPrediction[i], hybridPrediction[i], naivePrediction[i],
                    clamp(usedPrediction[i] + activeBand[0], 0.0, MAX_WAIT_MINUTES),
                    clamp(Math.max(usedPrediction[i] + activeBand[1], usedPrediction[i]), 0.0, MAX_WAIT_MINUTES)));
        }
        HorizonModel model = new HorizonModel(horizonMin, regressor, correctionUsed, activeBand[0], activeBand[1],
                queueMetrics, smoothedMetrics, hybridMetrics, naiveMetrics, List.copyOf(backtest));
        return new Trained(model, samples);
    }

    /**
     * Predicts the congestion of a gate for one horizon.
     *
     * @param model      trained model of the gate
     * @param history    current history (waiting visits and appointments are read from it)
     * @param now        instant of the forecast
     * @param horizonMin horizon in minutes (15, 30 or 60)
     * @return the prediction, with bounds, drivers and recommendations
     */
    public CongestionPrediction predict(TrainedCongestionModel model, GateHistory history, Instant now, int horizonMin) {
        if (horizonMin <= 0) {
            throw new IllegalArgumentException("the horizon must be positive");
        }
        HorizonModel horizon = model.horizon(horizonMin);
        Instant target = align(now.plus(horizonMin, ChronoUnit.MINUTES));
        double arrivalsPerHour = arrivalRate(history, target);
        int appointments = history.appointmentsBetween(target, target.plus(60, ChronoUnit.MINUTES));
        boolean holiday = CostaRicaCalendar.isHoliday(target);
        double arrivalRate = Math.max(arrivalsPerHour, appointments);
        double capacityPerHour = model.lanes() * 60.0 / model.serviceMinutes();
        double hours = horizonMin / 60.0;
        double projectedQueue = clamp(history.openVisits() + (arrivalRate - capacityPerHour) * hours, 0.0, MAX_QUEUE);
        double queueingWait = QueueingModel.waitAtHorizon(history.openVisits(), arrivalRate,
                model.serviceMinutes(), model.lanes(), horizonMin);
        double lastWait = recentWait(history, now, queueingWait);
        CongestionFeatures features = CongestionFeatures.at(target, holiday, history.openVisits(), appointments,
                model.lanes(), arrivalRate, model.serviceMinutes(), queueingWait, lastWait);
        double point = queueingWait;
        if (horizon.correctionUsed() && horizon.regressor().isFitted()) {
            point = queueingWait + horizon.regressor().predict(features.toVector());
        }
        point = clamp(point, 0.0, MAX_WAIT_MINUTES);

        double lower;
        double upper;
        if (horizon.correctionUsed()) {
            lower = point + horizon.residualP10();
            upper = point + horizon.residualP90();
        } else {
            QueueingResult steady = QueueingModel.mgcAllenCunneen(arrivalRate, model.serviceMinutes(), model.lanes(),
                    1.0, model.serviceCv2());
            double capacityPerMinute = model.lanes() / model.serviceMinutes();
            double backlog = Math.max(0.0,
                    history.openVisits() + arrivalRate * hours - capacityPerHour * hours) / capacityPerMinute;
            lower = QueueingModel.waitQuantile(steady, 0.10) + backlog;
            upper = QueueingModel.waitQuantile(steady, 0.90) + backlog;
        }
        lower = clamp(Math.min(lower, point), 0.0, MAX_WAIT_MINUTES);
        upper = clamp(Math.max(upper, point), 0.0, MAX_WAIT_MINUTES);
        RiskLevel risk = risk(point);
        double utilization = arrivalRate * model.serviceMinutes() / (60.0 * model.lanes());
        List<String> drivers = drivers(horizon, model, history, target, appointments, arrivalRate, utilization);
        List<String> recommendations = RecommendationRules.suggest(risk, model.lanes(), utilization, appointments,
                target, upper, holiday);
        return new CongestionPrediction(model.gateId(), model.gateCode(), horizonMin, point, lower, upper,
                Math.round(projectedQueue * 10.0) / 10.0, risk, model.confidence(), horizon.modelLabel(),
                round(arrivalsPerHour), Math.round(utilization * 1000.0) / 1000.0, drivers, recommendations);
    }

    private List<String> drivers(HorizonModel horizon, TrainedCongestionModel model, GateHistory history,
            Instant target, int appointments, double arrivalRate, double utilization) {
        List<String> out = new ArrayList<>();
        out.add(history.openVisits() + " camiones esperando un carril en la puerta " + model.gateCode()
                + " al iniciar el pronóstico");
        if (appointments > 0) {
            out.add(appointments + " citas programadas entre las " + hour(target) + " y las "
                    + hour(target.plus(60, ChronoUnit.MINUTES)));
        }
        if (CostaRicaCalendar.isHoliday(target)) {
            out.add("Feriado nacional: la demanda esperada es menor que un día hábil");
        }
        String rush = RecommendationRules.rushWindow(target);
        if (!rush.isEmpty()) {
            out.add("Franja " + rush + " con mayor llegada de camiones");
        }
        out.add(String.format(SPANISH,
                "%.1f llegadas/hora previstas para %d carriles con atención media de %.1f min (utilización %.0f %%)",
                arrivalRate, model.lanes(), model.serviceMinutes(), utilization * 100.0));
        if (horizon.correctionUsed()) {
            out.add(String.format(SPANISH, "Corrección de boosting a %d min entrenada con %d franjas (MAE %.1f min)",
                    horizon.horizonMin(), model.samples(), horizon.activeMetrics().mae()));
        } else {
            out.add("Histórico de " + model.samples()
                    + " franjas: el pronóstico usa solo el modelo de colas (sin corrección validada)");
        }
        return List.copyOf(out);
    }

    private static String hour(Instant instant) {
        return HOUR_FORMAT.format(instant.atZone(CostaRicaCalendar.ZONE));
    }

    /**
     * Arrival rate expected at the target bucket, from the seasonal profile of the history.
     *
     * @param history history of the gate
     * @param target  start of the target bucket
     * @return arrivals per hour
     */
    private static double arrivalRate(GateHistory history, Instant target) {
        ZonedDateTime local = target.atZone(CostaRicaCalendar.ZONE);
        int day = local.getDayOfWeek().getValue() - 1;
        int hour = local.getHour();
        Set<LocalDate> dates = new HashSet<>();
        int count = 0;
        for (HistoricalVisit visit : history.visits()) {
            ZonedDateTime visitLocal = visit.enteredAt().atZone(CostaRicaCalendar.ZONE);
            if (visitLocal.getDayOfWeek().getValue() - 1 == day && visitLocal.getHour() == hour) {
                dates.add(visitLocal.toLocalDate());
                count++;
            }
        }
        if (dates.isEmpty()) {
            return 0.0;
        }
        // one hour of history is one observation: the mean arrivals of that hour per date is
        // already the hourly rate (the seasonal profile of the training path works per bucket
        // and multiplies by four instead)
        return (double) count / dates.size();
    }

    /**
     * Mean wait observed in the previous hour, computed only with the visits whose service had
     * already started, so the value is available when the forecast is made.
     *
     * @param history  history of the gate
     * @param now      instant of the forecast
     * @param fallback value used when there is no completed visit
     * @return the mean wait in minutes
     */
    private static double recentWait(GateHistory history, Instant now, double fallback) {
        Instant from = now.minus(60, ChronoUnit.MINUTES);
        double sum = 0.0;
        int count = 0;
        for (HistoricalVisit visit : history.visits()) {
            Instant start = visit.enteredAt().plusSeconds(Math.round(visit.waitMinutes() * 60.0));
            if (!start.isBefore(from) && start.isBefore(now)) {
                sum += visit.waitMinutes();
                count++;
            }
        }
        return count == 0 ? fallback : sum / count;
    }

    private RiskLevel risk(double waitMinutes) {
        if (waitMinutes >= highRiskMinutes) {
            return RiskLevel.ALTO;
        }
        if (waitMinutes >= mediumRiskMinutes) {
            return RiskLevel.MEDIO;
        }
        return RiskLevel.BAJO;
    }

    private static ConfidenceLevel confidence(int samples, double mae) {
        if (samples < 60) {
            return ConfidenceLevel.BAJA;
        }
        if (samples < 200 || mae > 8.0) {
            return ConfidenceLevel.MEDIA;
        }
        return ConfidenceLevel.ALTA;
    }

    private static double meanServiceMinutes(List<HistoricalVisit> visits) {
        if (visits.isEmpty()) {
            return DEFAULT_SERVICE_MINUTES;
        }
        double sum = 0.0;
        int count = 0;
        for (HistoricalVisit visit : visits) {
            if (visit.serviceMinutes() > 0.0) {
                sum += clamp(visit.serviceMinutes(), MIN_SERVICE_MINUTES, MAX_SERVICE_MINUTES);
                count++;
            }
        }
        return count == 0 ? DEFAULT_SERVICE_MINUTES : sum / count;
    }

    private static double serviceCv2(List<HistoricalVisit> visits, double mean) {
        if (visits.size() < 10 || mean <= 0.0) {
            return DEFAULT_SERVICE_CV2;
        }
        double sum = 0.0;
        int count = 0;
        for (HistoricalVisit visit : visits) {
            if (visit.serviceMinutes() > 0.0) {
                double value = clamp(visit.serviceMinutes(), MIN_SERVICE_MINUTES, MAX_SERVICE_MINUTES);
                sum += (value - mean) * (value - mean);
                count++;
            }
        }
        if (count < 10) {
            return DEFAULT_SERVICE_CV2;
        }
        return Math.max(0.05, Math.min(4.0, sum / count / (mean * mean)));
    }

    /**
     * Number of trucks waiting for a lane at the start of every bucket.
     *
     * <p>A truck is waiting from its arrival until its service starts, so the trucks already
     * being served are not part of the queue.
     *
     * @param visits  closed visits, oldest first
     * @param origin  start of the series
     * @param buckets length of the series
     * @return the waiting queue per bucket
     */
    private static int[] waitingQueueLengths(List<HistoricalVisit> visits, Instant origin, int buckets) {
        int[] queue = new int[buckets];
        PriorityQueue<Instant> waiting = new PriorityQueue<>();
        int cursor = 0;
        for (int index = 0; index < buckets; index++) {
            Instant start = origin.plus((long) index * BUCKET_MINUTES, ChronoUnit.MINUTES);
            while (cursor < visits.size() && !visits.get(cursor).enteredAt().isAfter(start)) {
                HistoricalVisit visit = visits.get(cursor);
                waiting.add(visit.enteredAt().plusSeconds(Math.round(visit.waitMinutes() * 60.0)));
                cursor++;
            }
            while (!waiting.isEmpty() && !waiting.peek().isAfter(start)) {
                waiting.poll();
            }
            queue[index] = waiting.size();
        }
        return queue;
    }

    private static double arrivalsPerHour(double[] arrivals, int index) {
        int from = Math.max(0, index - BUCKETS_PER_HOUR);
        double sum = 0.0;
        for (int i = from; i < index; i++) {
            sum += arrivals[i];
        }
        int buckets = index - from;
        if (buckets == 0) {
            return arrivals[index] * (60.0 / BUCKET_MINUTES);
        }
        return sum / buckets * (60.0 / BUCKET_MINUTES);
    }

    private static double appointmentsWithin(double[] appointments, int index, int buckets) {
        double sum = 0.0;
        for (int i = index; i < Math.min(appointments.length, index + buckets); i++) {
            sum += appointments[i];
        }
        return sum;
    }

    private static double[] band(double[] predicted, double[] actual) {
        double[] residual = new double[predicted.length];
        for (int i = 0; i < predicted.length; i++) {
            residual[i] = actual[i] - predicted[i];
        }
        return new double[] {percentile(residual, 0.10), percentile(residual, 0.90)};
    }

    private static ModelMetrics evaluate(double[] actual, double[] prediction, double[] band) {
        double[] lower = new double[actual.length];
        double[] upper = new double[actual.length];
        for (int i = 0; i < actual.length; i++) {
            lower[i] = clamp(prediction[i] + band[0], 0.0, MAX_WAIT_MINUTES);
            upper[i] = clamp(Math.max(prediction[i] + band[1], prediction[i]), 0.0, MAX_WAIT_MINUTES);
        }
        return ModelMetrics.of(actual, prediction, lower, upper);
    }

    private static double percentile(double[] values, double quantile) {
        if (values.length == 0) {
            return 0.0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.round(quantile * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static double[] slice(double[] values, int from, int to) {
        return Arrays.copyOfRange(values, Math.min(from, values.length), Math.min(to, values.length));
    }

    private static int bucketIndex(Instant origin, Instant instant) {
        return (int) (Duration.between(origin, instant).toMinutes() / BUCKET_MINUTES);
    }

    /**
     * Aligns an instant to the start of its 15-minute bucket (UTC epoch, no DST in Costa Rica).
     *
     * @param instant any instant
     * @return the start of the bucket
     */
    public static Instant align(Instant instant) {
        long seconds = instant.getEpochSecond();
        return Instant.ofEpochSecond(Math.floorDiv(seconds, BUCKET_MINUTES * 60L) * BUCKET_MINUTES * 60L);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
