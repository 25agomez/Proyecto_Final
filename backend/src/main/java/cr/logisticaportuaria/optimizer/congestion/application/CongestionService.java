package cr.logisticaportuaria.optimizer.congestion.application;

import cr.logisticaportuaria.optimizer.congestion.application.dto.CongestionForecastResponse;
import cr.logisticaportuaria.optimizer.congestion.application.dto.HeatmapResponse;
import cr.logisticaportuaria.optimizer.congestion.domain.CongestionForecast;
import cr.logisticaportuaria.optimizer.congestion.domain.CongestionForecaster;
import cr.logisticaportuaria.optimizer.congestion.domain.CongestionPrediction;
import cr.logisticaportuaria.optimizer.congestion.domain.GateHistory;
import cr.logisticaportuaria.optimizer.congestion.domain.HeatmapBuilder;
import cr.logisticaportuaria.optimizer.congestion.domain.HistoricalVisit;
import cr.logisticaportuaria.optimizer.congestion.domain.HorizonModel;
import cr.logisticaportuaria.optimizer.congestion.domain.ModelMetrics;
import cr.logisticaportuaria.optimizer.congestion.domain.RiskLevel;
import cr.logisticaportuaria.optimizer.congestion.domain.TrainedCongestionModel;
import cr.logisticaportuaria.optimizer.congestion.infrastructure.mqtt.CongestionAlertBroadcaster;
import cr.logisticaportuaria.optimizer.congestion.infrastructure.mqtt.CongestionAlertPublisher;
import cr.logisticaportuaria.optimizer.congestion.infrastructure.persistence.CongestionForecastRepository;
import cr.logisticaportuaria.optimizer.congestion.infrastructure.persistence.CongestionHistoryRepository;
import cr.logisticaportuaria.optimizer.congestion.infrastructure.persistence.CongestionHistoryRepository.GateRef;
import cr.logisticaportuaria.shared.api.BusinessRuleException;
import cr.logisticaportuaria.shared.api.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Application service of the congestion forecast.
 *
 * <p>Loads the history of the gate, keeps the trained model in memory (retrained every
 * {@code app.congestion.retrain-minutes}) and answers the forecasts and the heatmap. The
 * scheduled job calls {@link #refreshAll(Instant)}, which persists the three horizons and
 * publishes the ALTO alerts.
 */
@Service
public class CongestionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CongestionService.class);

    /** Horizons of the prompt, in minutes. */
    public static final List<Integer> HORIZONS = CongestionForecaster.HORIZONS;

    private static final Set<Integer> VALID_HORIZONS = Set.copyOf(HORIZONS);
    private static final long MAX_HEATMAP_DAYS = 90L;

    private final CongestionHistoryRepository historyRepository;
    private final CongestionForecastRepository forecastRepository;
    private final CongestionProperties properties;
    private final CongestionAlertPublisher alertPublisher;
    private final CongestionAlertBroadcaster alertBroadcaster;
    private final CongestionForecaster forecaster;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(TrainedCongestionModel model, Instant trainedAt) {
    }

    /**
     * Constructor.
     *
     * @param historyRepository repository of the operational history
     * @param forecastRepository repository of the persisted forecasts
     * @param properties        configuration of the module
     * @param alertPublisher    MQTT publisher of the alerts
     * @param alertBroadcaster  STOMP broadcaster of the alerts
     */
    public CongestionService(CongestionHistoryRepository historyRepository,
            CongestionForecastRepository forecastRepository,
            CongestionProperties properties,
            CongestionAlertPublisher alertPublisher,
            CongestionAlertBroadcaster alertBroadcaster) {
        this.historyRepository = historyRepository;
        this.forecastRepository = forecastRepository;
        this.properties = properties;
        this.alertPublisher = alertPublisher;
        this.alertBroadcaster = alertBroadcaster;
        this.forecaster = new CongestionForecaster(properties.getSeasonLength(), properties.getMaxTrainingRows(),
                properties.getMediumRiskMinutes(), properties.getHighRiskMinutes());
    }

    /**
     * Forecast of one gate.
     *
     * @param gateId         gate
     * @param horizonMinutes 15, 30 or 60
     * @return the forecast
     */
    public CongestionForecastResponse forecast(UUID gateId, int horizonMinutes) {
        if (!VALID_HORIZONS.contains(horizonMinutes)) {
            throw new BusinessRuleException("Los horizontes válidos son 15, 30 y 60 minutos.");
        }
        GateRef gate = gate(gateId);
        Instant now = Instant.now();
        GateHistory history = loadHistory(gate, now);
        TrainedCongestionModel model = modelFor(gate, history, now);
        CongestionPrediction prediction = forecaster.predict(model, history, now, horizonMinutes);
        return toResponse(prediction, model, now);
    }

    /**
     * Historical heatmap of one gate or of every active gate.
     *
     * @param gateId optional gate
     * @param from   inclusive start
     * @param to     exclusive end
     * @return the heatmap
     */
    public HeatmapResponse heatmap(UUID gateId, Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw new BusinessRuleException("El fin del rango debe ser posterior al inicio.");
        }
        if (Duration.between(from, to).toDays() > MAX_HEATMAP_DAYS) {
            throw new BusinessRuleException("El rango máximo del mapa de calor es de 90 días.");
        }
        List<HistoricalVisit> visits;
        if (gateId != null) {
            visits = historyRepository.load(gate(gateId), from, to).visits();
        } else {
            List<HistoricalVisit> all = new ArrayList<>();
            for (GateRef gate : historyRepository.findActiveGates(properties.getYardId())) {
                all.addAll(historyRepository.load(gate, from, to).visits());
            }
            visits = all;
        }
        HeatmapBuilder.Heatmap heatmap = HeatmapBuilder.build(visits, from, to);
        List<HeatmapResponse.Cell> cells = heatmap.cells().stream()
                .map(cell -> new HeatmapResponse.Cell(cell.dayOfWeek(), cell.hour(),
                        Math.round(cell.averageWaitMin() * 10.0) / 10.0, cell.samples()))
                .toList();
        return new HeatmapResponse(gateId, from, to, cells, heatmap.matrix());
    }

    /**
     * Recomputes and persists the three horizons of every active gate.
     *
     * <p>All the rows of a run share the same {@code computed_at} (the unique key is
     * {@code gate_id, horizon_min, computed_at}) and the old rows are purged with the
     * retention window.
     *
     * @param now instant of the run
     * @return the forecasts of the run
     */
    public List<CongestionForecastResponse> refreshAll(Instant now) {
        List<GateRef> gates = historyRepository.findActiveGates(properties.getYardId());
        Instant computedAt = Instant.now();
        List<CongestionForecastResponse> responses = new ArrayList<>();
        List<CongestionForecast> rows = new ArrayList<>();
        for (GateRef gate : gates) {
            GateHistory history = loadHistory(gate, now);
            TrainedCongestionModel model = modelFor(gate, history, now);
            for (int horizon : HORIZONS) {
                CongestionPrediction prediction = forecaster.predict(model, history, now, horizon);
                rows.add(new CongestionForecast(gate.yardId(), gate.id(), horizon, prediction, computedAt));
                responses.add(toResponse(prediction, model, computedAt));
                if (prediction.riskLevel() == RiskLevel.ALTO) {
                    alertPublisher.publish(gate.yardId(), prediction);
                    alertBroadcaster.broadcast(gate.yardId(), prediction);
                }
            }
        }
        if (!rows.isEmpty()) {
            forecastRepository.saveAll(rows);
        }
        int purged = forecastRepository.deleteOlderThan(computedAt.minus(properties.getRetentionDays(),
                ChronoUnit.DAYS));
        LOGGER.info("Congestión recalculada: {} puertas, {} pronósticos, {} filas antiguas borradas.",
                gates.size(), rows.size(), purged);
        return responses;
    }

    /**
     * Latest persisted forecast of a gate and horizon.
     *
     * @param gateId     gate
     * @param horizonMin horizon
     * @return the latest row, when it exists
     */
    public java.util.Optional<CongestionForecast> latestPersisted(UUID gateId, int horizonMin) {
        return forecastRepository.findFirstByGateIdAndHorizonMinOrderByComputedAtDesc(gateId, horizonMin);
    }

    /** Clears the trained models, so the next request retrains. */
    public void invalidateCache() {
        cache.clear();
    }

    private GateRef gate(UUID gateId) {
        return historyRepository.findGate(gateId)
                .orElseThrow(() -> new NotFoundException("No existe una puerta activa con id " + gateId));
    }

    private GateHistory loadHistory(GateRef gate, Instant now) {
        return historyRepository.load(gate,
                now.minus(properties.getHistoryDays(), ChronoUnit.DAYS),
                now.plus(24, ChronoUnit.HOURS));
    }

    private TrainedCongestionModel modelFor(GateRef gate, GateHistory history, Instant now) {
        CacheEntry entry = cache.get(gate.id());
        if (entry != null && Duration.between(entry.trainedAt(), now).toMinutes() < properties.getRetrainMinutes()) {
            return entry.model();
        }
        TrainedCongestionModel model = forecaster.train(history, now);
        cache.put(gate.id(), new CacheEntry(model, now));
        LOGGER.debug("Modelo de congestión de {} reentrenado: {} franjas, MAE 15 min {} min, {} ms.", gate.code(),
                model.samples(), String.format(java.util.Locale.ROOT, "%.2f",
                        model.horizon(15).activeMetrics().mae()), model.trainingMillis());
        return model;
    }

    private CongestionForecastResponse toResponse(CongestionPrediction prediction, TrainedCongestionModel model,
            Instant computedAt) {
        HorizonModel horizon = model.horizon(prediction.horizonMin());
        ModelMetrics queue = horizon.queueMetrics();
        ModelMetrics smoothed = horizon.smoothedMetrics();
        ModelMetrics hybrid = horizon.hybridMetrics();
        ModelMetrics naive = horizon.naiveMetrics();
        ModelMetrics active = horizon.activeMetrics();
        CongestionForecastResponse.Quality quality = new CongestionForecastResponse.Quality(
                round(queue.mae()), round(smoothed.mae()), round(hybrid.mae()), round(naive.mae()), round(active.rmse()),
                round(active.mape()), Math.round(active.coverage80() * 1000.0) / 1000.0, model.samples(),
                horizon.modelLabel());
        return new CongestionForecastResponse(prediction.gateId(), prediction.gateCode(),
                prediction.horizonMin(), prediction.roundedWait(), round(prediction.p10()), round(prediction.p90()),
                prediction.queueLength(), prediction.riskLevel().name(), prediction.confidence().name(),
                prediction.model(), prediction.arrivalsPerHour(), prediction.utilization(), prediction.drivers(),
                prediction.recommendations(), computedAt, quality);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
