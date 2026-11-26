package cr.logisticaportuaria.fleet.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cr.logisticaportuaria.fleet.domain.VehicleState;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * In-memory cache with the last known state of every vehicle.
 *
 * <p>The cache is the read path of {@code GET /fleet/vehicles} and of the WebSocket
 * broadcast; the durable copy is {@code vehicle_last_state} (ADR-014). Entries expire after
 * 30 minutes without access, so a vehicle that stops sending telemetry disappears from the
 * cache and is reloaded from the database on demand.
 */
@Component
public class FleetStateCache {

    private final Cache<UUID, VehicleState> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    /**
     * @param vehicleId identifier of the vehicle
     * @return cached state, when present
     */
    public Optional<VehicleState> get(UUID vehicleId) {
        return Optional.ofNullable(cache.getIfPresent(vehicleId));
    }

    /**
     * @param state state to store
     */
    public void put(VehicleState state) {
        cache.put(state.vehicleId(), state);
    }

    /**
     * @return snapshot of every cached state
     */
    public List<VehicleState> all() {
        return List.copyOf(cache.asMap().values());
    }

    /**
     * Invalidates the cache (tests and administrative reloads).
     */
    public void clear() {
        cache.invalidateAll();
    }
}
