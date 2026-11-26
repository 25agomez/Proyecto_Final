package cr.logisticaportuaria.fleet.application.dto;

import java.util.List;

/**
 * Track of a vehicle as a GeoJSON LineString ({@code GET /fleet/vehicles/{id}/track}).
 *
 * <p>The coordinates are {@code [longitude, latitude]} pairs in WGS84, as GeoJSON mandates;
 * they are converted from the local metric frame through {@code YardFrame} (ADR-013).
 *
 * @param type        always {@code LineString}
 * @param coordinates pairs of longitude and latitude
 */
public record TrackResponse(String type, List<double[]> coordinates) {

    /**
     * @param coordinates pairs of longitude and latitude
     * @return the GeoJSON document
     */
    public static TrackResponse lineString(List<double[]> coordinates) {
        return new TrackResponse("LineString", coordinates);
    }
}
