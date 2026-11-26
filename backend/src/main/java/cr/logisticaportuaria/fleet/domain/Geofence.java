package cr.logisticaportuaria.fleet.domain;

import java.util.UUID;

/**
 * Geofence of the yard.
 *
 * @param id      identifier
 * @param yardId  identifier of the yard
 * @param name    name shown to the operator
 * @param kind    kind of zone
 * @param polygon geometry in the local metric frame
 */
public record Geofence(UUID id, UUID yardId, String name, GeofenceKind kind, GeoPolygon polygon) {

    /**
     * @return whether the zone forbids the presence of vehicles
     */
    public boolean isForbidden() {
        return kind == GeofenceKind.PROHIBIDA;
    }
}
