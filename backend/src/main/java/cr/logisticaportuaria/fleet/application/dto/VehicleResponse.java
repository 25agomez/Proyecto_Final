package cr.logisticaportuaria.fleet.application.dto;

import cr.logisticaportuaria.fleet.domain.VehicleState;
import java.time.Instant;
import java.util.UUID;

/**
 * Vehicle with its last known state ({@code GET /fleet/vehicles}).
 *
 * @param id          identifier
 * @param code        code
 * @param vehicleType type
 * @param status      status
 * @param lastState   last position, may be null
 * @param stale       whether the last measurement is older than the silence threshold
 */
public record VehicleResponse(UUID id, String code, String vehicleType, String status,
        LastState lastState, boolean stale) {

    /**
     * Builds the response from the domain state.
     *
     * @param state last state
     * @param stale whether the measurement is stale
     * @return the response
     */
    public static VehicleResponse from(VehicleState state, boolean stale) {
        return new VehicleResponse(state.vehicleId(), state.code(), state.vehicleType(),
                state.status().name(), new LastState(state.at(), state.x(), state.y(),
                        state.speedKmh(), state.batteryPct()), stale);
    }

    /**
     * Last known position.
     *
     * @param at         instant
     * @param x          {@code x} in the local frame, in meters
     * @param y          {@code y} in the local frame, in meters
     * @param speedKmh   speed
     * @param batteryPct battery, may be null
     */
    public record LastState(Instant at, double x, double y, Double speedKmh, Double batteryPct) {
    }
}
