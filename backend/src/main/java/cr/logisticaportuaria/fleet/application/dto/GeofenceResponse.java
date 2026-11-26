package cr.logisticaportuaria.fleet.application.dto;

import java.util.List;
import java.util.UUID;

/**
 * Geofence exposed to the dashboard, with its polygon in the local metric frame.
 *
 * @param id          identifier
 * @param yardId      yard
 * @param name        name
 * @param kind        kind
 * @param coordinates pairs of {@code x}/{@code y} in meters
 */
public record GeofenceResponse(UUID id, UUID yardId, String name, String kind,
        List<double[]> coordinates) {
}
