package cr.logisticaportuaria.yard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Logistics yard ({@code yard}). The timezone is stored with the yard because
 * the operational indicators are shown in the local time of the installation.
 */
@Entity
@Table(name = "yard")
public class Yard extends AbstractEntity {

    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "timezone", nullable = false)
    private String timezone = "America/Costa_Rica";

    @Column(name = "address")
    private String address;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Yard() {
        // for JPA
    }

    /**
     * Constructor.
     *
     * @param code     short code of the yard
     * @param name     name of the yard
     * @param timezone timezone of the yard
     */
    public Yard(String code, String name, String timezone) {
        this.code = code;
        this.name = name;
        if (timezone != null && !timezone.isBlank()) {
            this.timezone = timezone;
        }
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
