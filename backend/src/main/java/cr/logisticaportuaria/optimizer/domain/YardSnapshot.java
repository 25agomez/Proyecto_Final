package cr.logisticaportuaria.optimizer.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Snapshot of the yard used by the planners.
 *
 * <p>It is a mutable, single-threaded working copy: the planners place containers on it
 * while they evaluate the next move and throw it away when the request finishes. All the
 * slots live in a column index ({@code block-bay-row}) ordered by tier, which makes
 * support and blocking checks O(tier) instead of O(slots).
 */
public final class YardSnapshot {

    private final List<SlotNode> slots;
    private final Map<UUID, SlotNode> slotsById;
    private final Map<String, List<SlotNode>> columns;
    private final Map<UUID, ContainerUnit> occupancy;

    private YardSnapshot(List<SlotNode> slots, Map<UUID, ContainerUnit> occupancy) {
        this.slots = List.copyOf(slots);
        this.occupancy = new HashMap<>(occupancy);
        this.slotsById = new HashMap<>(slots.size() * 2);
        this.columns = new LinkedHashMap<>();
        for (SlotNode slot : this.slots) {
            slotsById.put(slot.id(), slot);
            columns.computeIfAbsent(columnKey(slot), key -> new ArrayList<>()).add(slot);
        }
        columns.values().forEach(column -> column.sort(Comparator.comparingInt(SlotNode::tier)));
    }

    /**
     * @param slots      slots of the yard
     * @param containers containers already stacked (with their slot)
     * @return the snapshot
     */
    public static YardSnapshot of(Collection<SlotNode> slots, Collection<ContainerUnit> containers) {
        List<SlotNode> ordered = new ArrayList<>(slots);
        ordered.sort(Comparator
                .comparing(SlotNode::blockCode)
                .thenComparingInt(SlotNode::row)
                .thenComparingInt(SlotNode::bay)
                .thenComparingInt(SlotNode::tier));
        Map<UUID, ContainerUnit> bySlot = new HashMap<>();
        for (ContainerUnit container : containers) {
            if (container.slotId() == null) {
                throw new IllegalArgumentException(
                        "El contenedor " + container.code() + " ya ubicado no tiene slot.");
            }
            bySlot.put(container.slotId(), container);
        }
        return new YardSnapshot(ordered, bySlot);
    }

    /**
     * @return an independent copy of the snapshot
     */
    public YardSnapshot copy() {
        return new YardSnapshot(slots, occupancy);
    }

    /**
     * @return all the slots, ordered by block, row, bay and tier
     */
    public List<SlotNode> slots() {
        return slots;
    }

    /**
     * @param id identifier of the slot
     * @return the slot, when it exists
     */
    public Optional<SlotNode> slot(UUID id) {
        return Optional.ofNullable(slotsById.get(id));
    }

    /**
     * @return the number of slots of the yard
     */
    public int totalSlots() {
        return slots.size();
    }

    /**
     * @return the number of stacked containers
     */
    public int occupiedSlots() {
        return occupancy.size();
    }

    /**
     * @param slot slot to check
     * @return whether the slot is free
     */
    public boolean isFree(SlotNode slot) {
        return !occupancy.containsKey(slot.id());
    }

    /**
     * @param slot slot to inspect
     * @return the container stacked there, when it exists
     */
    public Optional<ContainerUnit> containerAt(SlotNode slot) {
        return Optional.ofNullable(occupancy.get(slot.id()));
    }

    /**
     * @param slot slot of the stack
     * @return the slot immediately below, when it exists
     */
    public Optional<SlotNode> below(SlotNode slot) {
        return column(slot).stream()
                .filter(candidate -> candidate.tier() == slot.tier() - 1)
                .findFirst();
    }

    /**
     * @param slot slot of the stack
     * @return the slot immediately above, when it exists
     */
    public Optional<SlotNode> above(SlotNode slot) {
        return column(slot).stream()
                .filter(candidate -> candidate.tier() == slot.tier() + 1)
                .findFirst();
    }

    /**
     * @param slot slot of the stack
     * @return the containers stacked above it, bottom to top
     */
    public List<ContainerUnit> containersAbove(SlotNode slot) {
        List<ContainerUnit> above = new ArrayList<>();
        for (SlotNode candidate : column(slot)) {
            if (candidate.tier() > slot.tier()) {
                ContainerUnit unit = occupancy.get(candidate.id());
                if (unit != null) {
                    above.add(unit);
                }
            }
        }
        return above;
    }

    /**
     * @param slot slot of the stack
     * @return the whole column ({@code block-bay-row}), ordered by tier
     */
    public List<SlotNode> column(SlotNode slot) {
        return columns.getOrDefault(columnKey(slot), List.of(slot));
    }

    /**
     * Container of the lowest tier of a column. Used to score the client dispersion of a
     * neighbouring column without scanning the whole yard.
     *
     * @param blockCode block of the column
     * @param bay       bay of the column
     * @param row       row of the column
     * @return the container stacked at the lowest tier, when the column exists and is occupied
     */
    public Optional<ContainerUnit> lowestContainerAt(String blockCode, int bay, int row) {
        List<SlotNode> column = columns.get(columnKey(blockCode, bay, row));
        if (column == null || column.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(occupancy.get(column.get(0).id()));
    }

    /**
     * Places a container in a free slot, mutating the snapshot.
     *
     * @param slot      slot of the placement
     * @param container container to place
     * @return this snapshot (for chaining)
     */
    public YardSnapshot place(SlotNode slot, ContainerUnit container) {
        if (!isFree(slot)) {
            throw new IllegalStateException("El slot %s ya está ocupado.".formatted(slot.code()));
        }
        occupancy.put(slot.id(), container.at(slot.id()));
        return this;
    }

    /**
     * Removes the container of a slot, mutating the snapshot.
     *
     * @param slot slot to free
     * @return this snapshot (for chaining)
     */
    public YardSnapshot remove(SlotNode slot) {
        occupancy.remove(slot.id());
        return this;
    }

    /**
     * @return the stacked containers
     */
    public Collection<ContainerUnit> containers() {
        return List.copyOf(occupancy.values());
    }

    /**
     * @return the slots whose column is exactly one tier high (useful for FIFO baselines)
     */
    public List<SlotNode> highestSlots() {
        List<SlotNode> highest = new ArrayList<>();
        for (List<SlotNode> column : columns.values()) {
            highest.add(column.get(column.size() - 1));
        }
        return highest;
    }

    /**
     * @param blockCode block of the column
     * @param bay       bay of the column
     * @param row       row of the column
     * @return the key of the column
     */
    public static String columnKey(String blockCode, int bay, int row) {
        return blockCode + "|" + bay + "|" + row;
    }

    private static String columnKey(SlotNode slot) {
        return columnKey(slot.blockCode(), slot.bay(), slot.row());
    }
}
