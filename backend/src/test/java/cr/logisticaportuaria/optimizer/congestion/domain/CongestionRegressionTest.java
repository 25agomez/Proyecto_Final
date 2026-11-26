package cr.logisticaportuaria.optimizer.congestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cr.logisticaportuaria.optimizer.congestion.application.CongestionService;
import cr.logisticaportuaria.optimizer.congestion.support.CongestionScenarioSimulator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reproducible experiment of the congestion forecast.
 *
 * <p>Generates a fixed synthetic history with the discrete-event simulator, trains the model
 * with a temporal hold-out (first 80 % to fit, last 20 % to evaluate) and compares, for every
 * horizon, the hybrid model against the queueing baseline and the naive persistence baseline.
 * The measured numbers are written to {@code docs/experiments/EXP-005-congestion/} so the
 * report is reproducible with {@code ./mvnw test -Dtest=CongestionRegressionTest}.
 */
class CongestionRegressionTest {

    private static final UUID GATE = UUID.fromString("33333333-3333-7333-8333-333333333331");
    private static final UUID YARD = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final Instant START = LocalDate.of(2026, 9, 1)
            .atStartOfDay(CostaRicaCalendar.ZONE).toInstant();
    private static final int DAYS = 21;
    private static final Path REPORT = Path.of("..", "docs", "experiments", "EXP-005-congestion");

    @Test
    @DisplayName("every horizon improves the queueing baseline on a temporal hold-out without leakage")
    void everyHorizonImprovesTheQueueingBaseline() throws IOException {
        CongestionScenarioSimulator.Parameters parameters = CongestionScenarioSimulator.Parameters.reference();
        CongestionScenarioSimulator.Simulation simulation = CongestionScenarioSimulator.simulate(
                GATE, YARD, "G1", START, DAYS, parameters);
        Instant reference = START.plus(DAYS, ChronoUnit.DAYS);
        CongestionForecaster forecaster = new CongestionForecaster();

        long startNanos = System.nanoTime();
        TrainedCongestionModel model = forecaster.train(simulation.history(), reference);
        long trainMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(simulation.visits().size()).isGreaterThan(2_000);
        assertThat(model.samples()).isGreaterThan(500);
        assertThat(model.horizons()).containsExactlyInAnyOrder(15, 30, 60);
        assertThat(model.horizon(15).queueMetrics().samples()).isGreaterThan(100);
        assertThat(model.horizon(60).queueMetrics().samples()).isGreaterThan(100);
        assertThat(model.backtest()).isNotEmpty();

        for (int horizon : CongestionService.HORIZONS) {
            HorizonModel fitted = model.horizon(horizon);
            System.out.printf(Locale.ROOT,
                    "[P5] h=%2d franjas=%d modelo=%s colas MAE=%.2f RMSE=%.2f | suavizado MAE=%.2f RMSE=%.2f | "
                            + "hibrido MAE=%.2f RMSE=%.2f | ingenua MAE=%.2f | cobertura=%.3f%n",
                    horizon, fitted.queueMetrics().samples(), fitted.modelLabel(),
                    fitted.queueMetrics().mae(), fitted.queueMetrics().rmse(),
                    fitted.smoothedMetrics().mae(), fitted.smoothedMetrics().rmse(),
                    fitted.hybridMetrics().mae(), fitted.hybridMetrics().rmse(),
                    fitted.naiveMetrics().mae(), fitted.activeMetrics().coverage80());

            // the active model must beat the three baselines of the experiment
            assertThat(fitted.activeMetrics().mae()).isLessThan(fitted.queueMetrics().mae());
            assertThat(fitted.activeMetrics().mae()).isLessThan(fitted.smoothedMetrics().mae());
            assertThat(fitted.activeMetrics().mae()).isLessThan(fitted.naiveMetrics().mae());
            assertThat(fitted.activeMetrics().rmse()).isLessThan(fitted.queueMetrics().rmse());
            assertThat(fitted.activeMetrics().rmse()).isLessThan(fitted.smoothedMetrics().rmse());
            // and the error is bounded (the threshold is the measured value rounded up)
            assertThat(fitted.activeMetrics().mae()).isLessThan(12.0);
            assertThat(fitted.activeMetrics().rmse()).isGreaterThanOrEqualTo(fitted.activeMetrics().mae());
            // the 80 % band is neither empty nor trivially wide
            assertThat(fitted.activeMetrics().coverage80()).isBetween(0.75, 1.0);
        }
        System.out.printf(Locale.ROOT, "[P5] entrenamiento total %.0f ms (medido por el test: %d ms)%n",
                (double) model.trainingMillis(), trainMillis);

        double[] latencies = latencies(forecaster, model, simulation.history());
        double p50 = percentile(latencies, 0.50);
        double p95 = percentile(latencies, 0.95);
        double p99 = percentile(latencies, 0.99);
        System.out.printf(Locale.ROOT, "[P5] latencia pronóstico p50=%.2f ms p95=%.2f ms p99=%.2f ms%n",
                p50, p95, p99);
        assertThat(p95).isLessThan(100.0);

        // the forecast of the three horizons is coherent and explainable
        for (int horizon : CongestionService.HORIZONS) {
            CongestionPrediction prediction = forecaster.predict(model, simulation.history(), reference, horizon);
            assertThat(prediction.expectedWaitMin()).isBetween(0.0, QueueingModel.UNSTABLE_WAIT_MINUTES);
            assertThat(prediction.p10()).isLessThanOrEqualTo(prediction.expectedWaitMin());
            assertThat(prediction.p90()).isGreaterThanOrEqualTo(prediction.expectedWaitMin());
            assertThat(prediction.drivers()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(prediction.recommendations()).isNotEmpty();
            assertThat(prediction.confidence()).isEqualTo(model.confidence());
            assertThat(prediction.model()).isEqualTo(model.horizon(horizon).modelLabel());
        }

        writeReport(parameters, simulation, model, trainMillis, p50, p95, p99);
    }

    @Test
    @DisplayName("a gate with no history degrades to the queueing model with low confidence")
    void gateWithoutHistoryDegradesToTheQueueingModel() {
        GateHistory empty = new GateHistory(GATE, YARD, "G1", 3, List.of(), List.of(), 4);
        Instant now = Instant.parse("2026-09-01T15:00:00Z");
        CongestionForecaster forecaster = new CongestionForecaster();

        TrainedCongestionModel model = forecaster.train(empty, now);
        CongestionPrediction prediction = forecaster.predict(model, empty, now, 15);

        assertThat(model.samples()).isZero();
        assertThat(model.confidence()).isEqualTo(ConfidenceLevel.BAJA);
        assertThat(model.horizon(15).modelLabel()).isEqualTo("COLAS");
        assertThat(prediction.model()).isEqualTo("COLAS");
        assertThat(prediction.confidence()).isEqualTo(ConfidenceLevel.BAJA);
        assertThat(prediction.expectedWaitMin()).isGreaterThanOrEqualTo(0.0);
        assertThat(prediction.drivers()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(prediction.recommendations()).isNotEmpty();
    }

    @Test
    @DisplayName("the heatmap aggregates the wait per day of the week and hour")
    void heatmapAggregatesTheHistory() {
        CongestionScenarioSimulator.Simulation simulation = CongestionScenarioSimulator.simulate(
                GATE, YARD, "G1", START, 14, CongestionScenarioSimulator.Parameters.reference());
        Instant from = START;
        Instant to = START.plus(14, ChronoUnit.DAYS);

        HeatmapBuilder.Heatmap heatmap = HeatmapBuilder.build(simulation.visits(), from, to);

        assertThat(heatmap.matrix()).hasDimensions(7, 24);
        assertThat(heatmap.cells()).isNotEmpty();
        assertThat(heatmap.cells()).allSatisfy(cell -> {
            assertThat(cell.dayOfWeek()).isBetween(1, 7);
            assertThat(cell.hour()).isBetween(0, 23);
            assertThat(cell.samples()).isPositive();
        });
        int night = heatmap.cells().stream().filter(cell -> cell.hour() < 6).mapToInt(cell -> cell.samples()).sum();
        int morning = heatmap.cells().stream()
                .filter(cell -> cell.hour() >= 7 && cell.hour() < 10).mapToInt(cell -> cell.samples()).sum();
        assertThat(morning).isGreaterThan(night);
    }

    private static double[] latencies(CongestionForecaster forecaster, TrainedCongestionModel model,
            GateHistory history) {
        List<Double> all = new ArrayList<>();
        for (int horizon : CongestionService.HORIZONS) {
            for (double value : predictionLatencies(forecaster, model, history, horizon)) {
                all.add(value);
            }
        }
        return all.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double[] predictionLatencies(CongestionForecaster forecaster, TrainedCongestionModel model,
            GateHistory history, int horizon) {
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long start = System.nanoTime();
            forecaster.predict(model, history, START.plus(DAYS, ChronoUnit.DAYS).plus(i, ChronoUnit.MINUTES), horizon);
            durations.add(System.nanoTime() - start);
        }
        return durations.stream().mapToDouble(value -> value / 1_000_000.0).toArray();
    }

    private static double percentile(double[] values, double quantile) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.round(quantile * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static void writeReport(CongestionScenarioSimulator.Parameters parameters,
            CongestionScenarioSimulator.Simulation simulation, TrainedCongestionModel model, long trainMillis,
            double p50, double p95, double p99) throws IOException {
        Files.createDirectories(REPORT);
        for (int horizon : CongestionService.HORIZONS) {
            Files.writeString(REPORT.resolve("backtest-h" + horizon + ".csv"),
                    backtestCsv(model.horizon(horizon)), StandardCharsets.UTF_8);
        }
        Files.writeString(REPORT.resolve("metrics.md"), metricsMarkdown(parameters, simulation, model, trainMillis,
                p50, p95, p99), StandardCharsets.UTF_8);
    }

    private static String backtestCsv(HorizonModel model) {
        StringBuilder csv = new StringBuilder(
                "bucket,actual_min,queue_min,smoothed_min,hybrid_min,naive_min,p10,p90\n");
        for (BacktestRow row : model.backtest()) {
            csv.append(row.bucket()).append(',')
                    .append(fmt(row.actual())).append(',')
                    .append(fmt(row.queue())).append(',')
                    .append(fmt(row.smoothed())).append(',')
                    .append(fmt(row.hybrid())).append(',')
                    .append(fmt(row.naive())).append(',')
                    .append(fmt(row.p10())).append(',')
                    .append(fmt(row.p90())).append('\n');
        }
        return csv.toString();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String metricsMarkdown(CongestionScenarioSimulator.Parameters parameters,
            CongestionScenarioSimulator.Simulation simulation, TrainedCongestionModel model, long trainMillis,
            double p50, double p95, double p99) {
        StringBuilder md = new StringBuilder();
        md.append("# EXP-005 · Informe reproducible de congestión\n\n");
        md.append("Generado por `CongestionRegressionTest` ")
                .append("(`cd backend && ./mvnw test -Dtest=CongestionRegressionTest`). No editar a mano.\n\n");
        md.append("## Escenario simulado\n\n");
        md.append("| Parámetro | Valor |\n|---|---|\n");
        md.append("| Puerta | `").append(GATE).append("` (G1) |\n");
        md.append("| Ventana | ").append(START).append(" + ").append(DAYS).append(" días |\n");
        md.append("| Visitas simuladas | ").append(simulation.visits().size()).append(" |\n");
        md.append("| Carriles (servidores) | ").append(parameters.lanes()).append(" |\n");
        md.append("| Servicio medio | ").append(parameters.meanServiceMinutes()).append(" min (lognormal, sigma ")
                .append(parameters.serviceSigma()).append(") |\n");
        md.append("| Llegadas base | ").append(parameters.baseArrivalsPerHour()).append(" por hora ")
                .append("(punta mañana x2.0, punta tarde x1.7, noche x0.1, fin de semana x0.6, feriado x0.3) |\n");
        md.append("| Semilla | ").append(parameters.seed()).append(" |\n");
        md.append("| Citas | 60 % de las llegadas |\n\n");
        md.append("## Modelo\n\n");
        md.append("| Propiedad | Valor |\n|---|---|\n");
        md.append("| Confianza global | ").append(model.confidence()).append(" |\n");
        md.append("| Franjas de 15 min por horizonte | ").append(model.samples()).append(" |\n");
        md.append("| Partición temporal | 80 % entrenamiento / 20 % hold-out (sin fuga) |\n");
        md.append("| Servicio medio estimado | ").append(fmt(model.serviceMinutes())).append(" min (cv² ")
                .append(fmt(model.serviceCv2())).append(") |\n");
        md.append("| Tiempo de entrenamiento (3 horizontes) | ").append(trainMillis).append(" ms |\n");
        md.append("| Latencia del pronóstico | p50 ").append(fmt(p50)).append(" ms · p95 ")
                .append(fmt(p95)).append(" ms · p99 ").append(fmt(p99)).append(" ms |\n\n");
        md.append("## Métricas del hold-out temporal\n\n");
        md.append("| Horizonte | Modelo | MAE (min) | RMSE (min) | MAPE (%) | Cobertura p10-p90 | Filas |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (int horizon : CongestionService.HORIZONS) {
            HorizonModel fitted = model.horizon(horizon);
            md.append("| ").append(horizon).append(" min | Colas M/G/c | ")
                    .append(metricCells(fitted.queueMetrics())).append('\n');
            md.append("| ").append(horizon).append(" min | Suavizado HW | ")
                    .append(metricCells(fitted.smoothedMetrics())).append('\n');
            md.append("| ").append(horizon).append(" min | Híbrido | ")
                    .append(metricCells(fitted.hybridMetrics())).append('\n');
            md.append("| ").append(horizon).append(" min | Ingenua | ")
                    .append(metricCells(fitted.naiveMetrics())).append('\n');
        }
        md.append("\n> MAPE se calcula solo sobre filas con espera real > 0: con esperas de pocos segundos el\n")
                .append("> porcentaje se dispara y no es comparable entre modelos; MAE y RMSE sí lo son.\n\n");
        for (int horizon : CongestionService.HORIZONS) {
            HorizonModel fitted = model.horizon(horizon);
            md.append("- **").append(horizon).append(" min:** modelo activo **").append(fitted.modelLabel())
                    .append("**; ").append(fitted.correctionUsed()
                            ? "la corrección de boosting mejoró el MAE del modelo de colas en el hold-out."
                            : "la corrección de boosting no mejoró el MAE del modelo de colas, así que se descartó.")
                    .append('\n');
        }
        md.append("\n## Pronóstico en el instante de referencia\n\n");
        md.append("| Horizonte | Espera (min) | p10 | p90 | Cola | Riesgo | Modelo |\n|---|---|---|---|---|---|---|\n");
        CongestionForecaster forecaster = new CongestionForecaster();
        Instant reference = START.plus(DAYS, ChronoUnit.DAYS);
        for (int horizon : CongestionService.HORIZONS) {
            CongestionPrediction prediction = forecaster.predict(model, simulation.history(), reference, horizon);
            md.append("| ").append(horizon).append(" min | ").append(fmt(prediction.expectedWaitMin()))
                    .append(" | ").append(fmt(prediction.p10())).append(" | ").append(fmt(prediction.p90()))
                    .append(" | ").append(prediction.queueLength()).append(" | ")
                    .append(prediction.riskLevel()).append(" | ").append(prediction.model()).append(" |\n");
        }
        // illustrative peak inside the hold-out window: the reference instant is midnight, when
        // the gate is idle and every forecast is zero
        Instant peak = START.plus(16, ChronoUnit.DAYS).plus(17, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES);
        md.append("\n### Ejemplo ilustrativo en hora punta (17:30 local; no forma parte de la validación)\n\n");
        md.append("| Horizonte | Espera (min) | p10 | p90 | Cola | Riesgo | Modelo |\n|---|---|---|---|---|---|---|\n");
        for (int horizon : CongestionService.HORIZONS) {
            CongestionPrediction prediction = forecaster.predict(model, simulation.history(), peak, horizon);
            md.append("| ").append(horizon).append(" min | ").append(fmt(prediction.expectedWaitMin()))
                    .append(" | ").append(fmt(prediction.p10())).append(" | ").append(fmt(prediction.p90()))
                    .append(" | ").append(prediction.queueLength()).append(" | ")
                    .append(prediction.riskLevel()).append(" | ").append(prediction.model()).append(" |\n");
        }
        md.append("\n### Drivers del pronóstico a 15 min (17:30 local)\n\n");
        forecaster.predict(model, simulation.history(), peak, 15).drivers()
                .forEach(driver -> md.append("- ").append(driver).append('\n'));
        md.append("\n### Recomendaciones (17:30 local)\n\n");
        forecaster.predict(model, simulation.history(), peak, 15).recommendations()
                .forEach(recommendation -> md.append("- ").append(recommendation).append('\n'));
        md.append("\n## Hold-out por hora local (media, horizonte 15 min)\n\n");
        md.append(hourlyTable(model.horizon(15)));
        md.append("\n## Gráfico ASCII del hold-out (media por día, horizonte 15 min)\n\n```\n");
        md.append(dailyBars(model.horizon(15)));
        md.append("```\n\n## Gráfico ASCII del hold-out (media por día, horizonte 60 min)\n\n```\n");
        md.append(dailyBars(model.horizon(60)));
        md.append("```\n");
        return md.toString();
    }

    private static String metricCells(ModelMetrics metrics) {
        return fmt(metrics.mae()) + " | " + fmt(metrics.rmse()) + " | " + fmt(metrics.mape()) + " | "
                + fmt(metrics.coverage80()) + " | " + metrics.samples();
    }

    private static String hourlyTable(HorizonModel model) {
        double[] actual = new double[24];
        double[] hybrid = new double[24];
        double[] queue = new double[24];
        double[] smoothed = new double[24];
        int[] count = new int[24];
        for (BacktestRow row : model.backtest()) {
            int hour = row.bucket().atZone(CostaRicaCalendar.ZONE).getHour();
            actual[hour] += row.actual();
            hybrid[hour] += row.hybrid();
            queue[hour] += row.queue();
            smoothed[hour] += row.smoothed();
            count[hour]++;
        }
        StringBuilder md = new StringBuilder(
                "| Hora | Filas | Real | Híbrido | Suavizado | Colas |\n|---|---|---|---|---|---|\n");
        for (int hour = 0; hour < 24; hour++) {
            if (count[hour] == 0) {
                continue;
            }
            md.append("| %02d:00 | %d | %s | %s | %s | %s |%n".formatted(hour, count[hour],
                    fmt(actual[hour] / count[hour]), fmt(hybrid[hour] / count[hour]),
                    fmt(smoothed[hour] / count[hour]), fmt(queue[hour] / count[hour])));
        }
        return md.toString();
    }

    private static String dailyBars(HorizonModel model) {
        List<BacktestRow> rows = new ArrayList<>(model.backtest());
        rows.sort(Comparator.comparing(BacktestRow::bucket));
        StringBuilder chart = new StringBuilder();
        String currentDay = "";
        double actual = 0.0;
        double active = 0.0;
        int count = 0;
        for (BacktestRow row : rows) {
            String day = row.bucket().atZone(CostaRicaCalendar.ZONE).toLocalDate().toString();
            if (!day.equals(currentDay) && count > 0) {
                chart.append(bar(currentDay, actual / count, active / count, model));
                actual = 0.0;
                active = 0.0;
                count = 0;
            }
            currentDay = day;
            actual += row.actual();
            active += model.correctionUsed() ? row.hybrid() : row.queue();
            count++;
        }
        if (count > 0) {
            chart.append(bar(currentDay, actual / count, active / count, model));
        }
        return chart.toString();
    }

    private static String bar(String day, double actual, double predicted, HorizonModel model) {
        return "%s  real %5.1f %s%n           %s %5.1f %s%n".formatted(day, actual, "#".repeat(bars(actual)),
                model.correctionUsed() ? "hibr" : "cola", predicted, "=".repeat(bars(predicted)));
    }

    private static int bars(double minutes) {
        return (int) Math.max(0, Math.min(60, Math.round(minutes * 2.0)));
    }
}
