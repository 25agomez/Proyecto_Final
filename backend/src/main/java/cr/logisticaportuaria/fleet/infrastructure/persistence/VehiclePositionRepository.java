package cr.logisticaportuaria.fleet.infrastructure.persistence;

import cr.logisticaportuaria.fleet.domain.FleetStatus;
import cr.logisticaportuaria.fleet.domain.FleetTelemetry;
import cr.logisticaportuaria.fleet.domain.VehicleState;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Time series of the vehicle positions ({@code vehicle_position} and
 * {@code vehicle_last_state}).
 *
 * <p>The writes are plain JDBC on purpose: telemetry arrives every 1-5 s per vehicle and the
 * ORM adds no value for insert-only rows (ADR-014). The daily partition is created once per
 * day through {@code ensure_vehicle_position_partition} and cached in memory.
 */
@Repository
public class VehiclePositionRepository {

    private final JdbcTemplate jdbcTemplate;
    private volatile LocalDate ensuredPartition;

    /**
     * Constructor.
     *
     * @param jdbcTemplate template of the data source
     */
    public VehiclePositionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Ensures that the daily partition of the given instant exists.
     *
     * <p>The creation is synchronized per instance and tolerant of the {@code duplicate_table}
     * race: the SQL function checks {@code pg_class} before creating, so two instances (or two
     * threads) can both decide to create the same partition and one of them gets
     * {@code relation ... already exists}, which is harmless.
     *
     * @param at instant of the position
     */
    public synchronized void ensurePartitionFor(Instant at) {
        LocalDate day = at.atZone(ZoneOffset.UTC).toLocalDate();
        if (day.equals(ensuredPartition)) {
            return;
        }
        try {
            jdbcTemplate.query("SELECT ensure_vehicle_position_partition(?)",
                    (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null, day);
        } catch (org.springframework.dao.DataAccessException exception) {
            if (exception.getMessage() == null || !exception.getMessage().contains("already exists")) {
                throw exception;
            }
        }
        ensuredPartition = day;
    }

    /**
     * Inserts one position.
     *
     * @param telemetry telemetry to persist
     */
    public void insert(FleetTelemetry telemetry) {
        jdbcTemplate.update("""
                INSERT INTO vehicle_position
                    (vehicle_id, at, x, y, speed_kmh, heading_deg, battery_pct, fuel_pct,
                     status, work_order_id, container_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                telemetry.vehicleId(), Timestamp.from(telemetry.ts()), telemetry.x(),
                telemetry.y(), telemetry.speedKmh(), telemetry.headingDeg(),
                telemetry.batteryPct(), telemetry.fuelPct(), telemetry.status().name(),
                telemetry.workOrderId(), telemetry.containerCode());
    }

    /**
     * Upserts the last state, ignoring messages older than the stored one.
     *
     * @param telemetry telemetry to persist
     */
    public void upsertLastState(FleetTelemetry telemetry) {
        jdbcTemplate.update("""
                INSERT INTO vehicle_last_state
                    (vehicle_id, at, x, y, speed_kmh, status, work_order_id, container_code, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (vehicle_id) DO UPDATE SET
                    at = EXCLUDED.at,
                    x = EXCLUDED.x,
                    y = EXCLUDED.y,
                    speed_kmh = EXCLUDED.speed_kmh,
                    status = EXCLUDED.status,
                    work_order_id = EXCLUDED.work_order_id,
                    container_code = EXCLUDED.container_code,
                    updated_at = now()
                WHERE EXCLUDED.at >= vehicle_last_state.at
                """,
                telemetry.vehicleId(), Timestamp.from(telemetry.ts()), telemetry.x(),
                telemetry.y(), telemetry.speedKmh(), telemetry.status().name(),
                telemetry.workOrderId(), telemetry.containerCode());
    }

    /**
     * @param vehicleId identifier of the vehicle
     * @return last persisted state, when the vehicle has one
     */
    public Optional<VehicleState> findLastState(UUID vehicleId) {
        return jdbcTemplate.query("""
                SELECT v.id, v.yard_id, v.code, v.vehicle_type, vs.at, vs.x, vs.y,
                       COALESCE(vs.speed_kmh, 0) AS speed_kmh, COALESCE(vs.status, v.status) AS status,
                       vs.work_order_id, vs.container_code
                FROM vehicle v
                JOIN vehicle_last_state vs ON vs.vehicle_id = v.id
                WHERE v.id = ?
                """, (rs, rowNum) -> new VehicleState(
                        rs.getObject("id", UUID.class),
                        rs.getObject("yard_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("vehicle_type"),
                        FleetStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("at").toInstant(),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("speed_kmh"),
                        null,
                        null,
                        rs.getObject("work_order_id", UUID.class),
                        rs.getString("container_code")), vehicleId)
                .stream().findFirst();
    }

    /**
     * Returns the most recent {@code limit} points of a track, in chronological order.
     *
     * @param vehicleId identifier of the vehicle
     * @param from      inclusive start
     * @param to        exclusive end
     * @param limit     maximum number of points
     * @return points of the track
     */
    public List<TrackPoint> findTrack(UUID vehicleId, Instant from, Instant to, int limit) {
        return jdbcTemplate.query("""
                SELECT at, x, y, speed_kmh, status
                FROM (
                    SELECT at, x, y, speed_kmh, status
                    FROM vehicle_position
                    WHERE vehicle_id = ? AND at >= ? AND at < ?
                    ORDER BY at DESC
                    LIMIT ?
                ) recent
                ORDER BY at
                """, (rs, rowNum) -> new TrackPoint(
                        rs.getTimestamp("at").toInstant(),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getObject("speed_kmh") == null ? 0.0 : rs.getDouble("speed_kmh"),
                        rs.getString("status")),
                vehicleId, Timestamp.from(from), Timestamp.from(to), limit);
    }

    /**
     * @param yardId identifier of the yard
     * @param from   inclusive start
     * @param to     exclusive end
     * @param limit  maximum number of samples
     * @return samples used by the KPI aggregation, ordered by vehicle and time
     */
    public List<KpiSample> findSamples(UUID yardId, Instant from, Instant to, int limit) {
        return jdbcTemplate.query("""
                SELECT vp.vehicle_id, vp.at, vp.x, vp.y, COALESCE(vp.speed_kmh, 0) AS speed_kmh,
                       COALESCE(vp.status, v.status) AS status
                FROM vehicle_position vp
                JOIN vehicle v ON v.id = vp.vehicle_id
                WHERE v.yard_id = ? AND vp.at >= ? AND vp.at < ?
                ORDER BY vp.vehicle_id, vp.at
                LIMIT ?
                """, (rs, rowNum) -> new KpiSample(
                        rs.getObject("vehicle_id", UUID.class),
                        rs.getTimestamp("at").toInstant(),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("speed_kmh"),
                        FleetStatus.valueOf(rs.getString("status"))),
                yardId, Timestamp.from(from), Timestamp.from(to), limit);
    }

    /**
     * @param vehicleId identifier of the vehicle
     * @param from      inclusive start
     * @param to        exclusive end
     * @return number of persisted positions
     */
    public long count(UUID vehicleId, Instant from, Instant to) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM vehicle_position
                WHERE vehicle_id = ? AND at >= ? AND at < ?
                """, Long.class, vehicleId, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0 : count;
    }

    /**
     * @param vehicleId identifier of the vehicle
     * @return instant of the oldest position, when there is one
     */
    public Optional<Instant> firstPosition(UUID vehicleId) {
        return jdbcTemplate.query("""
                SELECT at FROM vehicle_position WHERE vehicle_id = ? ORDER BY at LIMIT 1
                """, (rs, rowNum) -> rs.getTimestamp("at").toInstant(), vehicleId)
                .stream().findFirst();
    }

    /**
     * Deletes the positions older than the given instant.
     *
     * @param threshold exclusive limit
     * @return number of deleted rows
     */
    public int deleteOlderThan(Instant threshold) {
        return jdbcTemplate.update("DELETE FROM vehicle_position WHERE at < ?",
                Timestamp.from(threshold));
    }

    /**
     * Keeps one point every {@code seconds} between the two instants.
     *
     * @param from    inclusive start
     * @param to      exclusive end
     * @param seconds size of the bucket
     * @return number of deleted rows
     */
    public int downsample(Instant from, Instant to, int seconds) {
        return jdbcTemplate.update("""
                WITH ranked AS (
                    SELECT id, at, vehicle_id,
                           row_number() OVER (
                               PARTITION BY vehicle_id,
                                   floor(extract(epoch FROM at) / ?)
                               ORDER BY at
                           ) AS position
                    FROM vehicle_position
                    WHERE at >= ? AND at < ?
                )
                DELETE FROM vehicle_position victim
                USING ranked
                WHERE victim.id = ranked.id
                  AND victim.at = ranked.at
                  AND ranked.position > 1
                """, seconds, Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * One point of a track.
     *
     * @param at       instant
     * @param x        position in the local frame
     * @param y        position in the local frame
     * @param speedKmh speed
     * @param status   status
     */
    public record TrackPoint(Instant at, double x, double y, double speedKmh, String status) {
    }

    /**
     * One sample of the KPI aggregation.
     *
     * @param vehicleId identifier of the vehicle
     * @param at        instant
     * @param x         position in the local frame
     * @param y         position in the local frame
     * @param speedKmh  speed
     * @param status    status
     */
    public record KpiSample(UUID vehicleId, Instant at, double x, double y, double speedKmh,
            FleetStatus status) {
    }
}
