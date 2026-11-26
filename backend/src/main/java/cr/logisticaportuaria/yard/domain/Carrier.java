package cr.logisticaportuaria.yard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Transport company ({@code carrier}).
 */
@Entity
@Table(name = "carrier")
public class Carrier extends AbstractEntity {

    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "tax_id")
    private String taxId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Carrier() {
        // for JPA
    }

    /**
     * Constructor.
     *
     * @param code  short code of the carrier
     * @param name  legal name
     * @param taxId tax identification (may be null)
     */
    public Carrier(String code, String name, String taxId) {
        this.code = code;
        this.name = name;
        this.taxId = taxId;
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

    public String getTaxId() {
        return taxId;
    }

    public void setTaxId(String taxId) {
        this.taxId = taxId;
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
