package cr.logisticaportuaria.yard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Position of a container in the yard ({@code container_position}).
 *
 * <p>Only one active position per container and per slot is allowed by the
 * database; the history is kept with {@code active = false}.
 */
@Entity
@Table(name = "container_position")
public class ContainerPosition extends AbstractEntity {

    @Column(name = "container_id", nullable = false)
    private UUID containerId;

    @Column(name = "slot_id", nullable = false)
    private UUID slotId;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt = Instant.now();

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ContainerPosition() {
        // for JPA
    }

    /**
     * Constructor.
     *
     * @param containerId container placed
     * @param slotId      slot where it was placed
     * @param placedAt    instant of the placement
     */
    public ContainerPosition(UUID containerId, UUID slotId, Instant placedAt) {
        this.containerId = containerId;
        this.slotId = slotId;
        if (placedAt != null) {
            this.placedAt = placedAt;
        }
    }

    /**
     * Marks the position as finished.
     *
     * @param at instant of the removal
     */
    public void remove(Instant at) {
        this.active = false;
        this.removedAt = at == null ? Instant.now() : at;
    }


    public UUID getContainerId() {
        return containerId;
    }

    public UUID getSlotId() {
        return slotId;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
