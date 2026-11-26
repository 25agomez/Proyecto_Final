package cr.logisticaportuaria.optimizer.congestion.domain;

import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * Features of one forecast, in the order expected by the model.
 *
 * <p>All of them are known when the forecast is made: the state belongs to the origin of the
 * forecast, the calendar to the target bucket (dates are deterministic) and the appointments
 * of the target hour are already scheduled.
 *
 * @param hourOfDay            hour of the day in Costa Rica (0..23)
 * @param dayOfWeek            ISO day of the week (1..7)
 * @param holiday              1 when the local date is a national holiday
 * @param waitingQueue         trucks waiting for a lane at the origin of the forecast
 * @param appointmentsNextHour appointments scheduled in the hour of the horizon
 * @param lanes                lanes of the gate
 * @param arrivalsPerHour      arrivals per hour assumed by the model
 * @param serviceMinutes       mean service time per lane
 * @param queueingWaitMin      wait predicted by the queueing model (baseline of the ML correction)
 * @param lastWaitMin          mean wait observed in the previous hour (persistence baseline)
 */
public record CongestionFeatures(
        double hourOfDay,
        double dayOfWeek,
        double holiday,
        double waitingQueue,
        double appointmentsNextHour,
        double lanes,
        double arrivalsPerHour,
        double serviceMinutes,
        double queueingWaitMin,
        double lastWaitMin) {

    /**
     * Builds the features of a forecast.
     *
     * @param bucketStart       start of the target bucket
     * @param holiday           whether the local date of the target is a holiday
     * @param waitingQueue      trucks waiting for a lane at the origin of the forecast
     * @param appointments      appointments in the hour of the horizon
     * @param lanes             lanes of the gate
     * @param arrivalsPerHour   arrivals per hour
     * @param serviceMinutes    mean service time per lane
     * @param queueingWaitMin   wait of the queueing baseline
     * @param lastWaitMin       mean wait observed in the previous hour
     * @return the features
     */
    public static CongestionFeatures at(Instant bucketStart, boolean holiday, double waitingQueue,
            double appointments, int lanes, double arrivalsPerHour, double serviceMinutes, double queueingWaitMin,
            double lastWaitMin) {
        ZonedDateTime local = bucketStart.atZone(CostaRicaCalendar.ZONE);
        return new CongestionFeatures(
                local.getHour() + local.getMinute() / 60.0,
                local.getDayOfWeek().getValue(),
                holiday ? 1.0 : 0.0,
                waitingQueue,
                appointments,
                lanes,
                arrivalsPerHour,
                serviceMinutes,
                queueingWaitMin,
                lastWaitMin);
    }

    /**
     * @return the vector fed to the regressor
     */
    public double[] toVector() {
        return new double[] {
            hourOfDay, dayOfWeek, holiday, waitingQueue, appointmentsNextHour, lanes,
            arrivalsPerHour, serviceMinutes, queueingWaitMin, lastWaitMin
        };
    }

    /**
     * @return the names of the features, in the same order as {@link #toVector()}
     */
    public static String[] names() {
        return new String[] {
            "hourOfDay", "dayOfWeek", "holiday", "waitingQueue", "appointmentsNextHour", "lanes",
            "arrivalsPerHour", "serviceMinutes", "queueingWaitMin", "lastWaitMin"
        };
    }
}
