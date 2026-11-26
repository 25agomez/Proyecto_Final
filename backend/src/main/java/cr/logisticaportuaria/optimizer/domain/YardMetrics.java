package cr.logisticaportuaria.optimizer.domain;


import java.util.Optional;

/**
 * Aggregated indicators of the yard, used by the plan response, the metrics endpoint and
 * the benchmark.
 *
 * @param totalSlots           slots of the yard
 * @param occupiedSlots        slots with a container
 * @param occupancy            occupied / total (0..1)
 * @param expectedRestows      expected unproductive moves if the current ETDs hold
 * @param averageDistanceMeters average distance to the gate of the stacked containers
 */
public record YardMetrics(
        int totalSlots,
        int occupiedSlots,
        double occupancy,
        double expectedRestows,
        double averageDistanceMeters) {

    /**
     * Computes the indicators of the snapshot.
     *
     * <p>A restow is counted for every pair (container, container above) where the one above
     * departs later: to extract the lower one, the upper one has to be moved first. Both
     * {@code REMOVE} moves of a restow (take out and put back) are not double counted; the
     * value is the number of blocking containers.
     *
     * @param snapshot state of the yard
     * @return the metrics
     */
    public static YardMetrics of(YardSnapshot snapshot) {
        int occupied = 0;
        double distanceSum = 0.0;
        double restows = 0.0;
        for (SlotNode slot : snapshot.slots()) {
            Optional<ContainerUnit> container = snapshot.containerAt(slot);
            if (container.isEmpty()) {
                continue;
            }
            occupied++;
            distanceSum += slot.distanceToGateMeters();
            long etd = container.get().etdMillisOrFarFuture();
            for (ContainerUnit above : snapshot.containersAbove(slot)) {
                if (above.etdMillisOrFarFuture() > etd) {
                    restows++;
                }
            }
        }
        int total = snapshot.totalSlots();
        double occupancy = total == 0 ? 0.0 : (double) occupied / total;
        double averageDistance = occupied == 0 ? 0.0 : distanceSum / occupied;
        return new YardMetrics(total, occupied, round(occupancy), round(restows), round(averageDistance));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
