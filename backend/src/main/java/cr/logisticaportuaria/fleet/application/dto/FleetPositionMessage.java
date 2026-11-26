package cr.logisticaportuaria.fleet.application.dto;

import cr.logisticaportuaria.fleet.domain.VehicleState;
import cr.logisticaportuaria.fleet.domain.YardFrame;
import java.time.Instant;
import java.util.UUID;

/**
 * Position broadcast over STOMP in {@code /topic/yards/{yardId}/fleet}.
 *
 * @param vehicleId     identifier
 * @param code          code
 * @param status        status
 * @param at            instant of the measurement
 * @param x             {@code x} in the local frame, in meters
 * @param y             {@code y} in the local frame, in meters
 * @param lat           latitude in degrees
 * @param lon           longitude in degrees
 * @param speedKmh      speed
 * @param batteryPct    battery, may be null
 * @param workOrderId   work order in progress, may be null
 * @param containerCode container being moved, may be null
 */
public record FleetPositionMessage(UUID vehicleId, String code, String status, Instant at,
        double x, double y, double lat, double lon, double speedKmh, Double batteryPct,
        UUID workOrderId, String containerCode) {

    /**
     * @param state state to broadcast
     * @return the message
     */
    public static FleetPositionMessage from(VehicleState state) {
        return new FleetPositionMessage(state.vehicleId(), state.code(), state.status().name(),
                state.at(), state.x(), state.y(), YardFrame.latOf(state.y()),
                YardFrame.lonOf(state.x()), state.speedKmh(), state.batteryPct(),
                state.workOrderId(), state.containerCode());
    }
}
