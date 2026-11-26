package cr.logisticaportuaria.optimizer.congestion.domain;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Historical heatmap of the wait per day of the week and hour, in Costa Rican time.
 */
public final class HeatmapBuilder {

    private static final int DAYS = 7;
    private static final int HOURS = 24;

    private HeatmapBuilder() {
    }

    /**
     * One cell of the heatmap.
     *
     * @param dayOfWeek       ISO day of the week (1 = Monday, 7 = Sunday)
     * @param hour            hour of the day in Costa Rica (0..23)
     * @param averageWaitMin  mean wait of the visits of the cell
     * @param samples         number of visits of the cell
     */
    public record Cell(int dayOfWeek, int hour, double averageWaitMin, int samples) {
    }

    /**
     * Heatmap of a window.
     *
     * @param from   inclusive start
     * @param to     exclusive end
     * @param cells  non-empty cells
     * @param matrix 7 x 24 matrix of mean waits in minutes (row 0 = Monday); 0 when there is no data
     */
    public record Heatmap(Instant from, Instant to, List<Cell> cells, double[][] matrix) {
    }

    /**
     * Builds the heatmap of the visits whose arrival falls in the window.
     *
     * @param visits visits of the gate
     * @param from   inclusive start
     * @param to     exclusive end
     * @return the heatmap
     */
    public static Heatmap build(List<HistoricalVisit> visits, Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("the window must be positive");
        }
        double[][] sum = new double[DAYS][HOURS];
        int[][] count = new int[DAYS][HOURS];
        for (HistoricalVisit visit : visits) {
            if (visit.enteredAt().isBefore(from) || !visit.enteredAt().isBefore(to)) {
                continue;
            }
            ZonedDateTime local = visit.enteredAt().atZone(CostaRicaCalendar.ZONE);
            int row = local.getDayOfWeek().getValue() - 1;
            int column = local.getHour();
            sum[row][column] += visit.waitMinutes();
            count[row][column]++;
        }
        double[][] matrix = new double[DAYS][HOURS];
        List<Cell> cells = new ArrayList<>();
        for (int day = 0; day < DAYS; day++) {
            for (int hour = 0; hour < HOURS; hour++) {
                if (count[day][hour] == 0) {
                    continue;
                }
                double average = sum[day][hour] / count[day][hour];
                matrix[day][hour] = average;
                cells.add(new Cell(day + 1, hour, average, count[day][hour]));
            }
        }
        return new Heatmap(from, to, List.copyOf(cells), matrix);
    }
}
