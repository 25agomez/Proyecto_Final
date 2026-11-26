package cr.logisticaportuaria.fleet.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Access to the work orders ({@code work_order}) and to the position of the slots.
 */
@Repository
public class WorkOrderRepository {

    public static final String PENDIENTE = "PENDIENTE";
    public static final String ASIGNADA = "ASIGNADA";
    public static final String EN_CURSO = "EN_CURSO";
    public static final String COMPLETADA = "COMPLETADA";
    public static final String CANCELADA = "CANCELADA";

    private static final RowMapper<WorkOrderRow> MAPPER = WorkOrderRepository::map;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor.
     *
     * @param jdbcTemplate template of the data source
     */
    public WorkOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param yardId      yard of the order
     * @param container   optional container code
     * @param orderType   type of order
     * @param fromSlotId  optional origin slot
     * @param toSlotId    optional destination slot
     * @param priority    priority between 1 and 10
     * @return identifier of the new order
     */
    public UUID create(UUID yardId, String container, String orderType, UUID fromSlotId,
            UUID toSlotId, int priority) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO work_order
                    (yard_id, container_code, order_type, from_slot_id, to_slot_id, priority, status)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDIENTE')
                RETURNING id
                """, UUID.class, yardId, container, orderType, fromSlotId, toSlotId, priority);
    }

    /**
     * @param id identifier of the order
     * @return the order, when it exists
     */
    public Optional<WorkOrderRow> findById(UUID id) {
        return jdbcTemplate.query("""
                SELECT wo.*, v.code AS vehicle_code
                FROM work_order wo
                LEFT JOIN vehicle v ON v.id = wo.vehicle_id
                WHERE wo.id = ?
                """, MAPPER, id).stream().findFirst();
    }

    /**
     * @param yardId identifier of the yard
     * @param status optional status filter
     * @return orders of the yard, newest first
     */
    public List<WorkOrderRow> findByYard(UUID yardId, String status) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT wo.*, v.code AS vehicle_code
                    FROM work_order wo
                    LEFT JOIN vehicle v ON v.id = wo.vehicle_id
                    WHERE wo.yard_id = ?
                    ORDER BY wo.created_at DESC
                    LIMIT 200
                    """, MAPPER, yardId);
        }
        return jdbcTemplate.query("""
                SELECT wo.*, v.code AS vehicle_code
                FROM work_order wo
                LEFT JOIN vehicle v ON v.id = wo.vehicle_id
                WHERE wo.yard_id = ? AND wo.status = ?
                ORDER BY wo.created_at DESC
                LIMIT 200
                """, MAPPER, yardId, status.toUpperCase());
    }

    /**
     * @param orderId   identifier of the order
     * @param vehicleId identifier of the assigned vehicle
     */
    public void assign(UUID orderId, UUID vehicleId) {
        jdbcTemplate.update("""
                UPDATE work_order SET vehicle_id = ?, status = 'ASIGNADA'
                WHERE id = ?
                """, vehicleId, orderId);
    }

    /**
     * @param orderId identifier of the order
     * @param at      start instant
     */
    public void start(UUID orderId, Instant at) {
        jdbcTemplate.update("""
                UPDATE work_order SET status = 'EN_CURSO', started_at = ? WHERE id = ?
                """, Timestamp.from(at), orderId);
    }

    /**
     * @param orderId identifier of the order
     * @param at      completion instant
     */
    public void complete(UUID orderId, Instant at) {
        jdbcTemplate.update("""
                UPDATE work_order SET status = 'COMPLETADA', completed_at = ? WHERE id = ?
                """, Timestamp.from(at), orderId);
    }

    /**
     * @param orderId identifier of the order
     */
    public void cancel(UUID orderId) {
        jdbcTemplate.update("UPDATE work_order SET status = 'CANCELADA' WHERE id = ?", orderId);
    }

    /**
     * @param yardId identifier of the yard
     * @return free vehicles with their last position
     */
    public List<FreeVehicle> findFreeVehicles(UUID yardId) {
        return jdbcTemplate.query("""
                SELECT v.id, v.code, vs.x, vs.y
                FROM vehicle v
                LEFT JOIN vehicle_last_state vs ON vs.vehicle_id = v.id
                WHERE v.yard_id = ? AND v.active = TRUE AND v.status = 'LIBRE'
                ORDER BY v.code
                """, (rs, rowNum) -> new FreeVehicle(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getObject("x") == null ? 0.0 : rs.getDouble("x"),
                        rs.getObject("y") == null ? 0.0 : rs.getDouble("y")), yardId);
    }

    /**
     * @param slotId identifier of the slot
     * @return position of the slot in the local frame, when it exists
     */
    public Optional<SlotPosition> findSlotPosition(UUID slotId) {
        return jdbcTemplate.query("""
                SELECT b.code AS block_code, s.bay, s.row
                FROM yard_slot s
                JOIN yard_block b ON b.id = s.block_id
                WHERE s.id = ?
                """, (rs, rowNum) -> new SlotPosition(
                        rs.getString("block_code"), rs.getInt("bay"), rs.getInt("row")), slotId)
                .stream().findFirst();
    }

    /**
     * @param yardId identifier of the yard
     * @param from   inclusive start of the completion
     * @param to     exclusive end of the completion
     * @return average cycle time in minutes of the completed orders
     */
    public Double averageCycleMinutes(UUID yardId, Instant from, Instant to) {
        return jdbcTemplate.queryForObject("""
                SELECT avg(EXTRACT(EPOCH FROM (completed_at - started_at)) / 60.0)
                FROM work_order
                WHERE yard_id = ? AND status = 'COMPLETADA' AND started_at IS NOT NULL
                  AND completed_at >= ? AND completed_at < ?
                """, Double.class, yardId, Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * @param yardId identifier of the yard
     * @param from   inclusive start
     * @param to     exclusive end
     * @return number of completed orders
     */
    public long countCompleted(UUID yardId, Instant from, Instant to) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM work_order
                WHERE yard_id = ? AND status = 'COMPLETADA' AND completed_at >= ? AND completed_at < ?
                """, Long.class, yardId, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0 : count;
    }

    /**
     * @param code container code
     * @return orders that moved the container, oldest first
     */
    public List<WorkOrderRow> findByContainer(String code) {
        return jdbcTemplate.query("""
                SELECT wo.*, v.code AS vehicle_code
                FROM work_order wo
                LEFT JOIN vehicle v ON v.id = wo.vehicle_id
                WHERE wo.container_code = ?
                ORDER BY wo.created_at
                """, MAPPER, code);
    }

    private static WorkOrderRow map(ResultSet rs, int rowNum) throws SQLException {
        return new WorkOrderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("yard_id", UUID.class),
                rs.getObject("vehicle_id", UUID.class),
                rs.getString("vehicle_code"),
                rs.getString("container_code"),
                rs.getString("order_type"),
                rs.getObject("from_slot_id", UUID.class),
                rs.getObject("to_slot_id", UUID.class),
                rs.getInt("priority"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }

    /**
     * Work order row.
     *
     * @param id            identifier
     * @param yardId        identifier of the yard
     * @param vehicleId     assigned vehicle, may be null
     * @param vehicleCode   code of the assigned vehicle, may be null
     * @param containerCode container, may be null
     * @param orderType     type of order
     * @param fromSlotId    origin slot, may be null
     * @param toSlotId      destination slot, may be null
     * @param priority      priority
     * @param status        status
     * @param createdAt     creation instant
     * @param startedAt     start instant, may be null
     * @param completedAt   completion instant, may be null
     */
    public record WorkOrderRow(UUID id, UUID yardId, UUID vehicleId, String vehicleCode,
            String containerCode, String orderType, UUID fromSlotId, UUID toSlotId, int priority,
            String status, Instant createdAt, Instant startedAt, Instant completedAt) {
    }

    /**
     * Free vehicle with its last position.
     *
     * @param id   identifier
     * @param code code
     * @param x    {@code x} in the local frame
     * @param y    {@code y} in the local frame
     */
    public record FreeVehicle(UUID id, String code, double x, double y) {
    }

    /**
     * Position of a slot in the local frame.
     *
     * @param blockCode code of the block
     * @param bay       bay
     * @param row       row
     */
    public record SlotPosition(String blockCode, int bay, int row) {
    }
}
