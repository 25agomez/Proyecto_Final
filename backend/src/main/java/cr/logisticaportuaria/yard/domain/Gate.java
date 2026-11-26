package cr.logisticaportuaria.yard.domain;

import cr.logisticaportuaria.shared.domain.Direction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import cr.logisticaportuaria.shared.persistence.AbstractEntity;

/**
 * Gate of the yard ({@code gate}).
 */
@Entity
@Table(name = "gate")
public class Gate extends AbstractEntity {

    @Column(name = "yard_id", nullable = false)
    private UUID yardId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private GateKind kind = GateKind.BOTH;

    @Column(name = "lanes", nullable = false)
    private int lanes = 1;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Gate() {
        // for JPA
    }

    /**
     * Constructor.
     *
     * @param yardId yard of the gate
     * @param code   code of the gate
     * @param name   name of the gate
     * @param kind   direction accepted by the gate
     * @param lanes  number of lanes
     */
    public Gate(UUID yardId, String code, String name, GateKind kind, int lanes) {
        this.yardId = yardId;
        this.code = code;
        this.name = name;
        this.kind = kind == null ? GateKind.BOTH : kind;
        this.lanes = lanes;
    }

    /**
     * @param direction direction of a visit
     * @return true when the gate accepts the direction
     */
    public boolean accepts(Direction direction) {
        if (direction == null) {
            return true;
        }
        return switch (kind) {
            case BOTH -> true;
            case IN -> direction == Direction.IN;
            case OUT -> direction == Direction.OUT;
        };
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public GateKind getKind() {
        return kind;
    }

    public void setKind(GateKind kind) {
        this.kind = kind;
    }

    public int getLanes() {
        return lanes;
    }

    public void setLanes(int lanes) {
        this.lanes = lanes;
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
