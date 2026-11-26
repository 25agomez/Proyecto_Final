package cr.logisticaportuaria.optimizer.congestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * History of one gate, the input of the congestion forecaster.
 *
 * @param gateId           identifier of the gate
 * @param yardId           identifier of the yard
 * @param gateCode         human readable code of the gate
 * @param lanes            number of lanes (servers of the queueing model)
 * @param visits           closed visits of the observation window, oldest first
 * @param appointmentTimes scheduled times of the upcoming appointments
 * @param openVisits       visits that have entered the gate but are still waiting for a lane
 */
public record GateHistory(
        UUID gateId,
        UUID yardId,
        String gateCode,
        int lanes,
        List<HistoricalVisit> visits,
        List<Instant> appointmentTimes,
        int openVisits) {

    /** @return a defensive copy with the visits sorted by arrival */
    public GateHistory {
        visits = visits.stream().sorted((left, right) -> left.enteredAt().compareTo(right.enteredAt())).toList();
    }

    /**
     * Number of appointments scheduled in a window.
     *
     * @param from inclusive start
     * @param to   exclusive end
     * @return the number of appointments
     */
    public int appointmentsBetween(Instant from, Instant to) {
        int count = 0;
        for (Instant appointment : appointmentTimes) {
            if (!appointment.isBefore(from) && appointment.isBefore(to)) {
                count++;
            }
        }
        return count;
    }
}
