package cr.logisticaportuaria.yard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Container ({@code container}).
 *
 * <p>The code follows the ISO 6346 (11 characters: 3 letters of the owner, U
 * or J or Z, 6 digits and the check digit). The database has a trigger that
 * rejects the codes with a wrong check digit.
 */
@Entity
@Table(name = "container")
public class Container extends AbstractEntity {

    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Column(name = "size_ft", nullable = false)
    private int sizeFt = 20;

    @Convert(converter = ContainerTypeConverter.class)
    @Column(name = "container_type", nullable = false)
    private ContainerType containerType = ContainerType.dry;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ContainerStatus status = ContainerStatus.IN_YARD;

    @Column(name = "weight_kg", precision = 10, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "client_code")
    private String clientCode;

    @Column(name = "eta")
    private Instant eta;

    @Column(name = "etd")
    private Instant etd;

    @Column(name = "imo_class")
    private String imoClass;

    @Column(name = "requires_power", nullable = false)
    private boolean requiresPower;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Container() {
        // for JPA
    }

    /**
     * Constructor.
     *
     * @param code          BIC code (ISO 6346)
     * @param sizeFt        size in feet (20 or 40)
     * @param containerType type of container
     * @param status        status of the container
     * @param clientCode    client that owns the container
     * @param eta           estimated arrival
     * @param etd           estimated departure
     * @param requiresPower whether the container needs a plugged outlet
     */
    public Container(String code, int sizeFt, ContainerType containerType, ContainerStatus status, String clientCode,
            Instant eta, Instant etd, boolean requiresPower) {
        this.code = code;
        this.sizeFt = sizeFt;
        this.containerType = containerType == null ? ContainerType.dry : containerType;
        this.status = status == null ? ContainerStatus.UNKNOWN : status;
        this.clientCode = clientCode;
        this.eta = eta;
        this.etd = etd;
        this.requiresPower = requiresPower;
    }


    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public int getSizeFt() {
        return sizeFt;
    }

    public void setSizeFt(int sizeFt) {
        this.sizeFt = sizeFt;
    }

    public ContainerType getContainerType() {
        return containerType;
    }

    public void setContainerType(ContainerType containerType) {
        this.containerType = containerType;
    }

    public ContainerStatus getStatus() {
        return status;
    }

    public void setStatus(ContainerStatus status) {
        this.status = status;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(BigDecimal weightKg) {
        this.weightKg = weightKg;
    }

    public String getClientCode() {
        return clientCode;
    }

    public void setClientCode(String clientCode) {
        this.clientCode = clientCode;
    }

    public Instant getEta() {
        return eta;
    }

    public void setEta(Instant eta) {
        this.eta = eta;
    }

    public Instant getEtd() {
        return etd;
    }

    public void setEtd(Instant etd) {
        this.etd = etd;
    }

    public String getImoClass() {
        return imoClass;
    }

    public void setImoClass(String imoClass) {
        this.imoClass = imoClass;
    }

    public boolean isRequiresPower() {
        return requiresPower;
    }

    public void setRequiresPower(boolean requiresPower) {
        this.requiresPower = requiresPower;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes == null ? new HashMap<>() : new HashMap<>(attributes);
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
