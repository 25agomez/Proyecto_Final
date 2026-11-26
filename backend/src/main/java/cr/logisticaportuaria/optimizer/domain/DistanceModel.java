package cr.logisticaportuaria.optimizer.domain;

/**
 * Derives the distance from a slot to the gate/bench.
 *
 * <p>The schema of P1 does not store a distance per slot, so the optimizer uses a
 * deterministic geometric model: blocks are laid out in alphabetical order, each block
 * starts 60 m after the previous one, every bay adds 6 m, every row adds 4 m and every
 * tier adds 1.5 m (the crane has to lift higher). The first block starts at 40 m. This is
 * a synthetic but stable model: the same yard always yields the same distances, so the
 * benchmark of EXP-004 is reproducible. When the architect adds a real distance column,
 * only this class has to change.
 */
public final class DistanceModel {

    /** Distance from the gate to the first block, in meters. */
    public static final double FIRST_BLOCK_METERS = 40.0;

    /** Distance added by each block, in meters. */
    public static final double METERS_PER_BLOCK = 60.0;

    /** Distance added by each bay, in meters. */
    public static final double METERS_PER_BAY = 6.0;

    /** Distance added by each row, in meters. */
    public static final double METERS_PER_ROW = 4.0;

    /** Extra distance added by each tier (lifting height), in meters. */
    public static final double METERS_PER_TIER = 1.5;

    private DistanceModel() {
    }

    /**
     * @param blockCode code of the block (the first letter defines its position)
     * @param bay       bay, starting at 1
     * @param row       row, starting at 1
     * @param tier      tier, starting at 1
     * @return the derived distance to the gate in meters
     */
    public static double distanceOf(String blockCode, int bay, int row, int tier) {
        int blockIndex = blockIndex(blockCode);
        double distance = FIRST_BLOCK_METERS
                + blockIndex * METERS_PER_BLOCK
                + (bay - 1) * METERS_PER_BAY
                + (row - 1) * METERS_PER_ROW
                + (tier - 1) * METERS_PER_TIER;
        return Math.round(distance * 10.0) / 10.0;
    }

    /**
     * @param blockCode code of the block
     * @return the position of the block, derived from its first letter ({@code A} is 0)
     */
    public static int blockIndex(String blockCode) {
        if (blockCode == null || blockCode.isBlank()) {
            return 0;
        }
        char first = Character.toUpperCase(blockCode.charAt(0));
        return Math.max(0, first - 'A');
    }
}
