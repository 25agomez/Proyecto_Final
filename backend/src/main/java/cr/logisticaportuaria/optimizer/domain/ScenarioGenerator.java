package cr.logisticaportuaria.optimizer.domain;

import cr.logisticaportuaria.yard.domain.ContainerType;
import cr.logisticaportuaria.yard.domain.SlotType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Deterministic generator of yard scenarios, used by the property tests, the performance
 * test and the benchmark of EXP-004.
 *
 * <p>Everything comes from a {@link Random} with a fixed seed and from a fixed reference
 * instant, so the same seed always produces the same yard and the same ETDs. The layout
 * mimics the demo yard of V2: 40 ft columns on the odd bays, 20 ft columns on the even
 * ones, blocks dedicated to reefer (with plugs) and to hazmat every few blocks.
 */
public final class ScenarioGenerator {

    /** Instant used as "now" by the generated ETDs. */
    public static final Instant REFERENCE = Instant.parse("2026-11-01T00:00:00Z");

    private static final String[] CLIENTS = {"ACME", "GLOBAL", "NORDICA", "PACIFICO", "RIO", "CENTRAL", "ANDES"};

    private ScenarioGenerator() {
    }

    /**
     * Generated scenario.
     *
     * @param yard     yard with the existing containers already stacked
     * @param incoming containers that arrive at the gate
     * @param seed     seed used to generate it
     */
    public record Scenario(YardSnapshot yard, List<ContainerUnit> incoming, long seed) {

        /**
         * @return an independent copy of the yard, so the caller can mutate it
         */
        public YardSnapshot yardCopy() {
            return yard.copy();
        }
    }

    /**
     * @param blocks   number of blocks
     * @param rows     rows per block
     * @param bays     bays per row
     * @param tiers    tiers per column
     * @return the slots of the yard
     */
    public static List<SlotNode> slots(int blocks, int rows, int bays, int tiers) {
        List<SlotNode> slots = new ArrayList<>();
        for (int block = 0; block < blocks; block++) {
            String blockCode = String.valueOf((char) ('A' + block));
            boolean hazmat = block % 5 == 4;
            boolean reefer = !hazmat && block % 5 == 3;
            SlotType slotType = hazmat ? SlotType.hazmat : reefer ? SlotType.reefer : SlotType.dry;
            for (int row = 1; row <= rows; row++) {
                for (int bay = 1; bay <= bays; bay++) {
                    int sizeFt = bay % 2 == 1 ? 40 : 20;
                    for (int tier = 1; tier <= tiers; tier++) {
                        slots.add(new SlotNode(
                                UUID.nameUUIDFromBytes(
                                        ("%s-%d-%d-%d".formatted(blockCode, bay, row, tier))
                                                .getBytes(StandardCharsets.UTF_8)),
                                blockCode, bay, row, tier, sizeFt, slotType, reefer,
                                tiers, DistanceModel.distanceOf(blockCode, bay, row, tier)));
                    }
                }
            }
        }
        return slots;
    }

    /**
     * Generates a full scenario.
     *
     * @param seed           seed of the generator
     * @param blocks         blocks of the yard
     * @param rows           rows per block
     * @param bays           bays per row
     * @param tiers          tiers per column
     * @param incomingCount  containers arriving at the gate
     * @param fillRatio      probability of occupying each tier while filling the yard
     * @return the scenario
     */
    public static Scenario generate(long seed, int blocks, int rows, int bays, int tiers, int incomingCount,
            double fillRatio) {
        Random random = new Random(seed);
        List<SlotNode> slots = slots(blocks, rows, bays, tiers);
        Map<String, SlotNode> index = new HashMap<>();
        for (SlotNode slot : slots) {
            index.put(key(slot), slot);
        }
        YardSnapshot yard = YardSnapshot.of(slots, List.of());
        int sequence = 0;
        for (int block = 0; block < blocks; block++) {
            String blockCode = String.valueOf((char) ('A' + block));
            for (int row = 1; row <= rows; row++) {
                for (int bay = 1; bay <= bays; bay++) {
                    Double lastWeight = null;
                    for (int tier = 1; tier <= tiers; tier++) {
                        SlotNode slot = index.get(key(blockCode, bay, row, tier));
                        if (random.nextDouble() >= fillRatio) {
                            break;
                        }
                        ContainerUnit unit = container("YRD%05d".formatted(sequence++), slot, random, lastWeight);
                        if (unit == null) {
                            break;
                        }
                        yard.place(slot, unit);
                        lastWeight = unit.weightKg();
                    }
                }
            }
        }
        List<ContainerUnit> incoming = new ArrayList<>();
        for (int i = 0; i < incomingCount; i++) {
            incoming.add(incoming("INB%05d".formatted(i), i, random));
        }
        return new Scenario(yard, incoming, seed);
    }

    /**
     * @param index  sequence of the container, used for the code and the ETD
     * @param random randomness source
     * @return an incoming container
     */
    public static ContainerUnit incoming(String prefix, int index, Random random) {
        int size = random.nextDouble() < 0.5 ? 40 : 20;
        double kind = random.nextDouble();
        ContainerType type = kind < 0.7 ? ContainerType.dry : kind < 0.9 ? ContainerType.reefer : ContainerType.hazmat;
        double weight = 4_000 + random.nextDouble() * 26_000;
        Instant etd = REFERENCE.plusSeconds((long) (random.nextDouble() * 45 * 86_400));
        String client = CLIENTS[index % CLIENTS.length];
        String code = bic(prefix, index);
        return ContainerUnit.incoming(code, size, type, weight, etd, random.nextDouble() * 12,
                client, type == ContainerType.reefer, type == ContainerType.hazmat ? "3" : null);
    }

    private static ContainerUnit container(String prefix, SlotNode slot, Random random, Double lastWeight) {
        ContainerType type = switch (slot.slotType()) {
            case hazmat -> ContainerType.hazmat;
            case reefer -> ContainerType.reefer;
            case dry -> ContainerType.dry;
        };
        double weight = lastWeight == null
                ? 4_000 + random.nextDouble() * 26_000
                : 4_000 + random.nextDouble() * Math.max(1.0, lastWeight - 4_000);
        Instant etd = REFERENCE.plusSeconds((long) (random.nextDouble() * 45 * 86_400));
        String client = CLIENTS[random.nextInt(CLIENTS.length)];
        return ContainerUnit.incoming(bic(prefix, random.nextInt(99_999)), slot.sizeFt(), type, weight, etd,
                random.nextDouble() * 12, client, type == ContainerType.reefer,
                type == ContainerType.hazmat ? "3" : null);
    }

    private static String key(SlotNode slot) {
        return key(slot.blockCode(), slot.bay(), slot.row(), slot.tier());
    }

    private static String key(String block, int bay, int row, int tier) {
        return block + "|" + bay + "|" + row + "|" + tier;
    }

    private static String bic(String prefix, int index) {
        String owner = (prefix + "XXX").substring(0, 3);
        return "%sU%07d".formatted(owner, index % 10_000_000);
    }
}
