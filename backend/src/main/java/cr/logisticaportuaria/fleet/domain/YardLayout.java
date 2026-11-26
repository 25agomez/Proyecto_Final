package cr.logisticaportuaria.fleet.domain;

/**
 * Layout of the slots in the local metric frame of the yard.
 *
 * <p>Uses the same geometry as {@code optimizer/domain/DistanceModel} (the schema does not
 * store a position per slot): the first block starts 40 m from the gate, every block adds
 * 60 m along {@code x}, every bay adds 6 m along {@code x} and every row adds 4 m along
 * {@code y}. The constants are duplicated on purpose to keep the fleet module independent
 * of the optimizer module; both agree because they describe the same physical yard
 * (ADR-013).
 */
public final class YardLayout {

    /** Distance from the gate to the first block, in meters. */
    public static final double FIRST_BLOCK_METERS = 40.0;

    /** Distance added by each block along {@code x}, in meters. */
    public static final double METERS_PER_BLOCK = 60.0;

    /** Distance added by each bay along {@code x}, in meters. */
    public static final double METERS_PER_BAY = 6.0;

    /** Distance added by each row along {@code y}, in meters. */
    public static final double METERS_PER_ROW = 4.0;

    private YardLayout() {
    }

    /**
     * @param blockCode code of the block (its first letter defines the position)
     * @param bay       bay, starting at 1
     * @param row       row, starting at 1
     * @return {@code x} of the slot in the local frame, in meters
     */
    public static double xOf(String blockCode, int bay, int row) {
        return FIRST_BLOCK_METERS
                + blockIndex(blockCode) * METERS_PER_BLOCK
                + (Math.max(1, bay) - 1) * METERS_PER_BAY;
    }

    /**
     * @param row row, starting at 1
     * @return {@code y} of the slot in the local frame, in meters
     */
    public static double yOf(int row) {
        return (Math.max(1, row) - 1) * METERS_PER_ROW;
    }

    /**
     * @param blockCode code of the block
     * @return position of the block, derived from its first letter ({@code A} is 0)
     */
    public static int blockIndex(String blockCode) {
        if (blockCode == null || blockCode.isBlank()) {
            return 0;
        }
        char first = Character.toUpperCase(blockCode.charAt(0));
        return Math.max(0, first - 'A');
    }
}
