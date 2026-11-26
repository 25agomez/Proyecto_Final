package cr.logisticaportuaria.fleet.domain;

/**
 * Conversion between WGS84 coordinates and the local metric frame of the yard.
 *
 * <p>The schema of P1 stores the vehicle positions as {@code x}/{@code y} in meters
 * ({@code NUMERIC(9,2)}) and the geofences as polygons in the same plane, while the MQTT
 * telemetry arrives with latitude and longitude. The yard is small (a few hundred meters),
 * so an equirectangular approximation around a fixed anchor is exact to the centimeter and
 * avoids a PostGIS dependency (see ADR-013).
 *
 * <p>{@code x} grows towards the east and {@code y} towards the north, both in meters from
 * the anchor.
 */
public final class YardFrame {

    /** Latitude of the origin of the yard frame (Puerto Limón, Costa Rica). */
    public static final double ANCHOR_LAT = 9.9908;

    /** Longitude of the origin of the yard frame (Puerto Limón, Costa Rica). */
    public static final double ANCHOR_LON = -83.0350;

    /** Meters per degree of latitude. */
    public static final double METERS_PER_DEGREE_LAT = 111_320.0;

    private YardFrame() {
    }

    /**
     * @return meters per degree of longitude at the anchor latitude
     */
    public static double metersPerDegreeLon() {
        return METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(ANCHOR_LAT));
    }

    /**
     * @param lon longitude in degrees
     * @return {@code x} of the local frame, in meters
     */
    public static double xOf(double lon) {
        return (lon - ANCHOR_LON) * metersPerDegreeLon();
    }

    /**
     * @param lat latitude in degrees
     * @return {@code y} of the local frame, in meters
     */
    public static double yOf(double lat) {
        return (lat - ANCHOR_LAT) * METERS_PER_DEGREE_LAT;
    }

    /**
     * @param x {@code x} of the local frame, in meters
     * @return longitude in degrees
     */
    public static double lonOf(double x) {
        return ANCHOR_LON + x / metersPerDegreeLon();
    }

    /**
     * @param y {@code y} of the local frame, in meters
     * @return latitude in degrees
     */
    public static double latOf(double y) {
        return ANCHOR_LAT + y / METERS_PER_DEGREE_LAT;
    }
}
