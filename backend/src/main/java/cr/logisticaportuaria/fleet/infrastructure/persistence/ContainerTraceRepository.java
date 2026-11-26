package cr.logisticaportuaria.fleet.infrastructure.persistence;


import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only access to the history of a container, used by the trace endpoint.
 *
 * <p>It crosses four modules (gate, yard, fleet) with plain SQL on read-only queries: the
 * fleet module must not change the schema of the others (MEMORY.md §11).
 */
@Repository
public class ContainerTraceRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor.
     *
     * @param jdbcTemplate template of the data source
     */
    public ContainerTraceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param code container code
     * @return internal identifier of the container, when it exists
     */
    public Optional<UUID> findContainerId(String code) {
        return jdbcTemplate.query("SELECT id FROM container WHERE code = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class), code).stream().findFirst();
    }

    /**
     * @param code container code
     * @return OCR readings of the container
     */
    public List<GateReading> findGateEvents(String code) {
        return jdbcTemplate.query("""
                SELECT ge.observed_at, g.code AS gate_code, ge.kind, ge.confidence, ge.iso_valid
                FROM gate_event ge
                JOIN gate g ON g.id = ge.gate_id
                WHERE ge.normalized_text = ?
                ORDER BY ge.observed_at
                """, (rs, rowNum) -> new GateReading(
                        rs.getTimestamp("observed_at").toInstant(),
                        rs.getString("gate_code"),
                        rs.getString("kind"),
                        rs.getObject("confidence") == null ? null : rs.getDouble("confidence"),
                        rs.getObject("iso_valid") == null ? null : rs.getBoolean("iso_valid")), code);
    }

    /**
     * @param code container code
     * @return visits whose appointment mentions the container
     */
    public List<VisitReading> findVisits(String code) {
        return jdbcTemplate.query("""
                SELECT vl.entered_at, vl.exited_at, vl.direction, vl.state, vl.result,
                       g.code AS gate_code, t.plate
                FROM visit_log vl
                JOIN gate g ON g.id = vl.gate_id
                LEFT JOIN truck t ON t.id = vl.truck_id
                WHERE vl.container_code = ?
                ORDER BY vl.entered_at
                """, (rs, rowNum) -> new VisitReading(
                        rs.getTimestamp("entered_at").toInstant(),
                        rs.getTimestamp("exited_at") == null ? null : rs.getTimestamp("exited_at").toInstant(),
                        rs.getString("direction"),
                        rs.getString("state"),
                        rs.getString("result"),
                        rs.getString("gate_code"),
                        rs.getString("plate")), code);
    }

    /**
     * @param containerId identifier of the container
     * @return placements of the container
     */
    public List<PlacementReading> findPlacements(UUID containerId) {
        return jdbcTemplate.query("""
                SELECT cp.placed_at, cp.removed_at, b.code AS block_code, s.bay, s.row, s.tier
                FROM container_position cp
                JOIN yard_slot s ON s.id = cp.slot_id
                JOIN yard_block b ON b.id = s.block_id
                WHERE cp.container_id = ?
                ORDER BY cp.placed_at
                """, (rs, rowNum) -> new PlacementReading(
                        rs.getTimestamp("placed_at").toInstant(),
                        rs.getTimestamp("removed_at") == null ? null : rs.getTimestamp("removed_at").toInstant(),
                        rs.getString("block_code"),
                        rs.getInt("bay"),
                        rs.getInt("row"),
                        rs.getInt("tier")), containerId);
    }

    /**
     * @param code container code
     * @return work orders that moved the container
     */
    public List<WorkOrderReading> findWorkOrders(String code) {
        return jdbcTemplate.query("""
                SELECT wo.created_at, wo.started_at, wo.completed_at, wo.status, wo.order_type,
                       v.code AS vehicle_code
                FROM work_order wo
                LEFT JOIN vehicle v ON v.id = wo.vehicle_id
                WHERE wo.container_code = ?
                ORDER BY wo.created_at
                """, (rs, rowNum) -> new WorkOrderReading(
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                        rs.getString("status"),
                        rs.getString("order_type"),
                        rs.getString("vehicle_code")), code);
    }

    /**
     * OCR reading of the gate.
     *
     * @param at         instant
     * @param gateCode   gate
     * @param kind       kind of reading
     * @param confidence confidence
     * @param isoValid   ISO 6346 check digit result
     */
    public record GateReading(Instant at, String gateCode, String kind, Double confidence,
            Boolean isoValid) {
    }

    /**
     * Visit of a truck.
     *
     * @param enteredAt entry instant
     * @param exitedAt  exit instant, may be null
     * @param direction direction
     * @param state     state
     * @param result    result
     * @param gateCode  gate
     * @param plate     plate of the truck
     */
    public record VisitReading(Instant enteredAt, Instant exitedAt, String direction, String state,
            String result, String gateCode, String plate) {
    }

    /**
     * Placement of the container in the yard.
     *
     * @param placedAt  placement instant
     * @param removedAt removal instant, may be null
     * @param blockCode block
     * @param bay       bay
     * @param row       row
     * @param tier      tier
     */
    public record PlacementReading(Instant placedAt, Instant removedAt, String blockCode, int bay,
            int row, int tier) {
    }

    /**
     * Work order that moved the container.
     *
     * @param createdAt  creation instant
     * @param startedAt  start instant, may be null
     * @param completedAt completion instant, may be null
     * @param status     status
     * @param orderType  type of order
     * @param vehicleCode vehicle
     */
    public record WorkOrderReading(Instant createdAt, Instant startedAt, Instant completedAt,
            String status, String orderType, String vehicleCode) {
    }
}
