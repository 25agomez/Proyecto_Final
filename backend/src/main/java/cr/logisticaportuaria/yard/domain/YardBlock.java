package cr.logisticaportuaria.yard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Block of the yard ({@code yard_block}): a group of slots with the same
 * restrictions (maximum tier, reefer only, hazmat only).
 */
@Entity
@Table(name = "yard_block")
public class YardBlock extends AbstractEntity {

    @Column(name = "yard_id", nullable = false)
    private UUID yardId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "max_tier", nullable = false)
    private int maxTier = 3;

    @Column(name = "reefer_only", nullable = false)
    private boolean reeferOnly;

    @Column(name = "hazmat_only", nullable = false)
    private boolean hazmatOnly;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected YardBlock() {
        // for JPA
    }

    /**
     * Constructor.
     *
     * @param yardId      yard of the block
     * @param code        code of the block
     * @param maxTier     maximum stacking height
     * @param reeferOnly  whether the block only accepts reefers
     * @param hazmatOnly  whether the block only accepts hazardous cargo
     */
    public YardBlock(UUID yardId, String code, int maxTier, boolean reeferOnly, boolean hazmatOnly) {
        this.yardId = yardId;
        this.code = code;
        this.maxTier = maxTier;
        this.reeferOnly = reeferOnly;
        this.hazmatOnly = hazmatOnly;
    }


    public UUID getYardId() {
        return yardId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public int getMaxTier() {
        return maxTier;
    }

    public void setMaxTier(int maxTier) {
        this.maxTier = maxTier;
    }

    public boolean isReeferOnly() {
        return reeferOnly;
    }

    public void setReeferOnly(boolean reeferOnly) {
        this.reeferOnly = reeferOnly;
    }

    public boolean isHazmatOnly() {
        return hazmatOnly;
    }

    public void setHazmatOnly(boolean hazmatOnly) {
        this.hazmatOnly = hazmatOnly;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
