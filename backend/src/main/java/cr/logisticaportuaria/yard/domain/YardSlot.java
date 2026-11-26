package cr.logisticaportuaria.yard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Slot of the yard ({@code yard_slot}): a physical position of 20 feet. A 40
 * feet container occupies two consecutive bays, so its slot spans
 * {@code bay_span = 2}.
 */
@Entity
@Table(name = "yard_slot")
public class YardSlot extends AbstractEntity {

    @Column(name = "block_id", nullable = false)
    private UUID blockId;

    @Column(name = "bay", nullable = false)
    private int bay;

    @Column(name = "row", nullable = false)
    private int row;

    @Column(name = "tier", nullable = false)
    private int tier;

    @Column(name = "size_ft", nullable = false)
    private int sizeFt = 20;

    @Column(name = "bay_span", insertable = false, updatable = false)
    private int baySpan;

    @Convert(converter = SlotTypeConverter.class)
    @Column(name = "slot_type", nullable = false)
    private SlotType slotType = SlotType.dry;

    @Column(name = "has_power", nullable = false)
    private boolean hasPower;

    @Column(name = "capacity_teu", nullable = false)
    private BigDecimal capacityTeu = BigDecimal.ONE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected YardSlot() {
        // for JPA
    }

    /**
     * Constructor.
     *
     * @param blockId   block of the slot
     * @param bay       bay number
     * @param row       row number
     * @param tier      tier (level) of the slot
     * @param sizeFt    size of the container that fits (20 or 40)
     * @param slotType  type of cargo accepted
     * @param hasPower  whether the slot has a power outlet (reefer)
     * @param capacityTeu capacity in TEU
     */
    public YardSlot(UUID blockId, int bay, int row, int tier, int sizeFt, SlotType slotType, boolean hasPower,
            BigDecimal capacityTeu) {
        this.blockId = blockId;
        this.bay = bay;
        this.row = row;
        this.tier = tier;
        this.sizeFt = sizeFt;
        this.slotType = slotType == null ? SlotType.dry : slotType;
        this.hasPower = hasPower;
        if (capacityTeu != null) {
            this.capacityTeu = capacityTeu;
        }
    }


    public UUID getBlockId() {
        return blockId;
    }

    public int getBay() {
        return bay;
    }

    public int getRow() {
        return row;
    }

    public int getTier() {
        return tier;
    }

    public int getSizeFt() {
        return sizeFt;
    }

    public SlotType getSlotType() {
        return slotType;
    }

    public void setSlotType(SlotType slotType) {
        this.slotType = slotType;
    }

    public boolean isHasPower() {
        return hasPower;
    }

    public void setHasPower(boolean hasPower) {
        this.hasPower = hasPower;
    }

    public BigDecimal getCapacityTeu() {
        return capacityTeu;
    }

    public int getBaySpan() {
        return baySpan;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
