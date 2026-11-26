package cr.logisticaportuaria.fleet.infrastructure.persistence;

import cr.logisticaportuaria.fleet.domain.FleetStatus;
import cr.logisticaportuaria.fleet.domain.VehicleInfo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Access to the master data of the vehicles ({@code vehicle}).
 */
@Repository
public class FleetVehicleRepository {

    private static final String COLUMNS = "id, yard_id, code, vehicle_type, status";

    private static final RowMapper<VehicleInfo> MAPPER = (rs, rowNum) -> new VehicleInfo(
            rs.getObject("id", UUID.class),
            rs.getObject("yard_id", UUID.class),
            rs.getString("code"),
            rs.getString("vehicle_type"),
            FleetStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor.
     *
     * @param jdbcTemplate template of the data source
     */
    public FleetVehicleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return every active vehicle, ordered by code
     */
    public List<VehicleInfo> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM vehicle WHERE active = TRUE ORDER BY code", MAPPER);
    }

    /**
     * @param id identifier of the vehicle
     * @return the vehicle, when it exists and is active
     */
    public Optional<VehicleInfo> findById(UUID id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM vehicle WHERE id = ? AND active = TRUE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * @return identifier of the only yard of the database, when there is one
     */
    public Optional<UUID> singleYard() {
        return jdbcTemplate.query("SELECT id FROM yard ORDER BY created_at LIMIT 1",
                (rs, rowNum) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }

    /**
     * Updates the operational status of a vehicle.
     *
     * @param id     identifier of the vehicle
     * @param status new status
     */
    public void updateStatus(UUID id, FleetStatus status) {
        jdbcTemplate.update("UPDATE vehicle SET status = ? WHERE id = ?", status.name(), id);
    }
}
