package cr.logisticaportuaria.fleet.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * One telemetry message published in {@code yard/{yardId}/fleet/{vehicleId}/telemetry}.
 *
 * @param vehicleId     identifier of the vehicle
 * @param ts            instant of the measurement
 * @param lat           latitude in degrees
 * @param lon           longitude in degrees
 * @param speedKmh      speed in km/h
 * @param headingDeg    heading in degrees (0 = north)
 * @param batteryPct    battery percentage, may be null for fuel vehicles
 * @param fuelPct       fuel percentage, may be null for electric vehicles
 * @param status        operational status
 * @param workOrderId   work order in progress, may be null
 * @param containerCode container being moved, may be null
 */
public record FleetTelemetry(
        UUID vehicleId,
        Instant ts,
        double lat,
        double lon,
        double speedKmh,
        double headingDeg,
        Double batteryPct,
        Double fuelPct,
        FleetStatus status,
        UUID workOrderId,
        String containerCode) {

    /**
     * Normalizes the container code (uppercase, no spaces) and validates the ranges of the
     * payload that do not depend on the database.
     *
     * @return the telemetry with the normalized container code
     * @throws IllegalArgumentException when a field is out of range
     */
    public FleetTelemetry normalized() {
        if (vehicleId == null) {
            throw new IllegalArgumentException("vehicleId is required");
        }
        if (ts == null) {
            throw new IllegalArgumentException("ts is required");
        }
        if (lat < -90 || lat > 90) {
            throw new IllegalArgumentException("lat out of range");
        }
        if (lon < -180 || lon > 180) {
            throw new IllegalArgumentException("lon out of range");
        }
        if (speedKmh < 0 || speedKmh > 200) {
            throw new IllegalArgumentException("speedKmh out of range");
        }
        if (headingDeg < 0 || headingDeg > 360) {
            throw new IllegalArgumentException("headingDeg out of range");
        }
        if (batteryPct != null && (batteryPct < 0 || batteryPct > 100)) {
            throw new IllegalArgumentException("batteryPct out of range");
        }
        if (fuelPct != null && (fuelPct < 0 || fuelPct > 100)) {
            throw new IllegalArgumentException("fuelPct out of range");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        String code = containerCode == null ? null
                : containerCode.replace(" ", "").toUpperCase(Locale.ROOT);
        if (code != null && code.isEmpty()) {
            code = null;
        }
        return new FleetTelemetry(vehicleId, ts, lat, lon, speedKmh, headingDeg, batteryPct, fuelPct,
                status, workOrderId, code);
    }

    /**
     * @return {@code x} of the local frame, in meters
     */
    public double x() {
        return YardFrame.xOf(lon);
    }

    /**
     * @return {@code y} of the local frame, in meters
     */
    public double y() {
        return YardFrame.yOf(lat);
    }
}
