package cr.logisticaportuaria.fleet.domain;

import java.util.List;

/**
 * Geometry helpers for the tracks.
 */
public final class TrackGeometry {

    private TrackGeometry() {
    }

    /**
     * @param x1 first {@code x}
     * @param y1 first {@code y}
     * @param x2 second {@code x}
     * @param y2 second {@code y}
     * @return euclidean distance between both points, in meters
     */
    public static double distance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.hypot(dx, dy);
    }

    /**
     * Builds the GeoJSON LineString of a track in WGS84 coordinates.
     *
     * <p>GeoJSON mandates {@code [longitude, latitude]} order, so the local {@code x}/{@code y}
     * are converted back through {@link YardFrame}.
     *
     * @param xs {@code x} of the points in the local frame
     * @param ys {@code y} of the points in the local frame
     * @return {@code [lon, lat]} pairs
     */
    public static List<double[]> toLonLat(List<Double> xs, List<Double> ys) {
        int size = Math.min(xs.size(), ys.size());
        List<double[]> coordinates = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            coordinates.add(new double[] {YardFrame.lonOf(xs.get(i)), YardFrame.latOf(ys.get(i))});
        }
        return coordinates;
    }
}
