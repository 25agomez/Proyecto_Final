package cr.logisticaportuaria.optimizer.congestion.infrastructure.persistence;

import cr.logisticaportuaria.optimizer.congestion.domain.GateHistory;
import cr.logisticaportuaria.optimizer.congestion.domain.HistoricalVisit;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only access to the operational tables used by the congestion model.
 *
 * <p>The tables {@code visit_log}, {@code appointment} and {@code gate} belong to the gate
 * and yard modules, so this repository only reads them with SQL: it never changes their
 * schema nor their entities.
 *
 * <p>Two proxies are documented in {@code MEMORY.md} §5 because the schema does not
 * separate the queue from the service at the gate: the wait is {@code wait_seconds}
 * (arrival to validation) and the service time is the interval between the arrival and the
 * assignment to a dock ({@code assigned_at}), capped by the model.
 */
@Repository
public class CongestionHistoryRepository {

    private static final String GATES_SQL = """
            SELECT id, yard_id, code, name, lanes
            FROM gate
            WHERE active = TRUE
            """;

    private static final String VISITS_SQL = """
            SELECT entered_at,
                   exited_at,
                   COALESCE(wait_seconds / 60.0,
                            EXTRACT(EPOCH FROM (COALESCE(validated_at, entered_at) - entered_at)) / 60.0)
                       AS wait_minutes,
                   EXTRACT(EPOCH FROM (COALESCE(assigned_at, validated_at, entered_at) - entered_at)) / 60.0
                       AS service_minutes
            FROM visit_log
            WHERE gate_id = ?
              AND entered_at >= ?
              AND entered_at < ?
              AND exited_at IS NOT NULL
            ORDER BY entered_at
            """;

    private static final String APPOINTMENTS_SQL = """
            SELECT scheduled_at
            FROM appointment
            WHERE gate_id = ?
              AND scheduled_at >= ?
              AND scheduled_at < ?
              AND status <> 'CANCELADA'
            ORDER BY scheduled_at
            """;

    private static final String OPEN_VISITS_SQL = """
            SELECT count(*)
            FROM visit_log
            WHERE gate_id = ?
              AND exited_at IS NULL
              AND assigned_at IS NULL
              AND state <> 'CANCELADO'
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor.
     *
     * @param jdbcTemplate template of the data source
     */
    public CongestionHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Gate of the module.
     *
     * @param id           identifier
     * @param yardId       yard of the gate
     * @param code         code of the gate
     * @param name         name of the gate
     * @param lanes        number of lanes
     */
    public record GateRef(UUID id, UUID yardId, String code, String name, int lanes) {
    }

    /**
     * Active gates, optionally filtered by yard.
     *
     * @param yardId yard filter ({@code null} for every yard)
     * @return the gates ordered by code
     */
    public List<GateRef> findActiveGates(UUID yardId) {
        if (yardId == null) {
            return jdbcTemplate.query(GATES_SQL + " ORDER BY code", this::mapGate);
        }
        return jdbcTemplate.query(GATES_SQL + " AND yard_id = ? ORDER BY code", this::mapGate, yardId);
    }

    /**
     * One active gate.
     *
     * @param gateId identifier
     * @return the gate, when it exists and is active
     */
    public Optional<GateRef> findGate(UUID gateId) {
        return jdbcTemplate.query(GATES_SQL + " AND id = ?", this::mapGate, gateId).stream().findFirst();
    }

    /**
     * Loads the history of a gate.
     *
     * @param gate     gate
     * @param from     inclusive start of the observation window
     * @param to       exclusive end of the observation window
     * @return the history
     */
    public GateHistory load(GateRef gate, Instant from, Instant to) {
        List<HistoricalVisit> visits = jdbcTemplate.query(VISITS_SQL, (resultSet, index) -> new HistoricalVisit(
                resultSet.getObject("entered_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("exited_at", OffsetDateTime.class).toInstant(),
                resultSet.getDouble("wait_minutes"),
                resultSet.getDouble("service_minutes")),
                gate.id(), Timestamp.from(from), Timestamp.from(to));
        List<Instant> appointments = jdbcTemplate.query(APPOINTMENTS_SQL,
                (resultSet, index) -> resultSet.getObject("scheduled_at", OffsetDateTime.class).toInstant(),
                gate.id(), Timestamp.from(from), Timestamp.from(to));
        Integer open = jdbcTemplate.queryForObject(OPEN_VISITS_SQL, Integer.class, gate.id());
        return new GateHistory(gate.id(), gate.yardId(), gate.code(), gate.lanes(), visits, appointments,
                open == null ? 0 : open);
    }

    private GateRef mapGate(java.sql.ResultSet resultSet, int row) throws java.sql.SQLException {
        return new GateRef(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("yard_id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("name"),
                resultSet.getInt("lanes"));
    }
}
