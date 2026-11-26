package cr.logisticaportuaria.fleet.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.logisticaportuaria.fleet.domain.Geofence;
import cr.logisticaportuaria.fleet.domain.GeofenceKind;
import cr.logisticaportuaria.fleet.domain.GeoPolygon;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read access to the geofences ({@code geofence}).
 */
@Repository
public class GeofenceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Constructor.
     *
     * @param jdbcTemplate template of the data source
     * @param objectMapper mapper of the {@code geojson} column
     */
    public GeofenceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * @param yardId identifier of the yard
     * @return active geofences of the yard
     */
    public List<Geofence> findActiveByYard(UUID yardId) {
        List<Geofence> geofences = new ArrayList<>();
        jdbcTemplate.query("""
                SELECT id, yard_id, name, kind, geojson
                FROM geofence
                WHERE yard_id = ? AND active = TRUE
                ORDER BY name
                """, rs -> {
                    JsonNode geojson = readJson(rs.getString("geojson"));
                    geofences.add(new Geofence(
                            rs.getObject("id", UUID.class),
                            rs.getObject("yard_id", UUID.class),
                            rs.getString("name"),
                            GeofenceKind.valueOf(rs.getString("kind")),
                            GeoPolygon.fromGeoJson(geojson)));
                }, yardId);
        return geofences;
    }

    private JsonNode readJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            throw new IllegalStateException("invalid geojson in geofence", exception);
        }
    }
}
