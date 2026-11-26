package cr.logisticaportuaria.fleet.domain;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Polygon of a geofence in the local metric frame of the yard.
 *
 * <p>Only the outer ring of a GeoJSON {@code Polygon} is used: the zones of the demo are
 * simple and convex enough for the ray casting test, and the schema does not use holes
 * (ADR-013). The coordinates are meters, not degrees, because the database stores the
 * positions in the local frame.
 */
public final class GeoPolygon {

    private final double[][] ring;

    private GeoPolygon(double[][] ring) {
        this.ring = ring;
    }

    /**
     * Builds the polygon from the {@code geojson} column.
     *
     * @param geojson JSON document of the geofence
     * @return the polygon
     * @throws IllegalArgumentException when the document is not a Polygon with a usable ring
     */
    public static GeoPolygon fromGeoJson(JsonNode geojson) {
        if (geojson == null) {
            throw new IllegalArgumentException("geojson is required");
        }
        String type = geojson.path("type").asText("");
        if (!"Polygon".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("only GeoJSON Polygon geofences are supported");
        }
        JsonNode coordinates = geojson.path("coordinates");
        if (!coordinates.isArray() || coordinates.isEmpty()) {
            throw new IllegalArgumentException("the polygon has no coordinates");
        }
        JsonNode outer = coordinates.get(0);
        if (!outer.isArray() || outer.size() < 3) {
            throw new IllegalArgumentException("the outer ring needs at least three points");
        }
        double[][] ring = new double[outer.size()][2];
        for (int i = 0; i < outer.size(); i++) {
            JsonNode point = outer.get(i);
            if (!point.isArray() || point.size() < 2) {
                throw new IllegalArgumentException("invalid point in the outer ring");
            }
            ring[i][0] = point.get(0).asDouble();
            ring[i][1] = point.get(1).asDouble();
        }
        return new GeoPolygon(ring);
    }

    /**
     * @param x {@code x} in the local frame
     * @param y {@code y} in the local frame
     * @return whether the point is inside the outer ring
     */
    public boolean contains(double x, double y) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0];
            double yi = ring[i][1];
            double xj = ring[j][0];
            double yj = ring[j][1];
            boolean crosses = (yi > y) != (yj > y)
                    && x < (xj - xi) * (y - yi) / (yj - yi) + xi;
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * @return centroid of the outer ring
     */
    public double[] centroid() {
        double x = 0;
        double y = 0;
        for (double[] point : ring) {
            x += point[0];
            y += point[1];
        }
        return new double[] {x / ring.length, y / ring.length};
    }

    /**
     * @return number of points of the outer ring
     */
    public int size() {
        return ring.length;
    }

    /**
     * @return copy of the points of the outer ring
     */
    public double[][] points() {
        double[][] copy = new double[ring.length][2];
        for (int i = 0; i < ring.length; i++) {
            copy[i][0] = ring[i][0];
            copy[i][1] = ring[i][1];
        }
        return copy;
    }
}
