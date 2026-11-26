package cr.logisticaportuaria.yard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Truck of a transport company ({@code truck}).
 *
 * <p>The plate is stored normalized (upper case, without dashes) and it is
 * personal data: the logs never print it and the retention is configurable.
 */
@Entity
@Table(name = "truck")
public class Truck extends AbstractEntity {

    @Column(name = "plate", nullable = false, unique = true)
    private String plate;

    @Column(name = "carrier_id")
    private UUID carrierId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Truck() {
        // for JPA
    }

    /**
     * Constructor.
     *
     * @param plate     normalized plate
     * @param carrierId carrier of the truck (may be null)
     * @param driverId  driver of the truck (may be null)
     */
    public Truck(String plate, UUID carrierId, UUID driverId) {
        this.plate = plate;
        this.carrierId = carrierId;
        this.driverId = driverId;
    }


    public String getPlate() {
        return plate;
    }

    public void setPlate(String plate) {
        this.plate = plate;
    }

    public UUID getCarrierId() {
        return carrierId;
    }

    public void setCarrierId(UUID carrierId) {
        this.carrierId = carrierId;
    }

    public UUID getDriverId() {
        return driverId;
    }

    public void setDriverId(UUID driverId) {
        this.driverId = driverId;
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
