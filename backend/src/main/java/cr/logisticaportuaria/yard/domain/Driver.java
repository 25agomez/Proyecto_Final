package cr.logisticaportuaria.yard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Driver ({@code driver}). The license and the phone are personal data: they
 * are only stored with the consent of the operator and never leave the API
 * without the role that requires them.
 */
@Entity
@Table(name = "driver")
public class Driver extends AbstractEntity {

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "license_id")
    private String licenseId;

    @Column(name = "phone")
    private String phone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Driver() {
        // for JPA
    }

    /**
     * Constructor.
     *
     * @param fullName  name of the driver
     * @param licenseId license identification (may be null)
     * @param phone     contact phone (may be null)
     */
    public Driver(String fullName, String licenseId, String phone) {
        this.fullName = fullName;
        this.licenseId = licenseId;
        this.phone = phone;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getLicenseId() {
        return licenseId;
    }

    public void setLicenseId(String licenseId) {
        this.licenseId = licenseId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
