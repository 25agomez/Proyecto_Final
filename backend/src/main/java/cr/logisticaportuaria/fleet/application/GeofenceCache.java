package cr.logisticaportuaria.fleet.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cr.logisticaportuaria.fleet.domain.Geofence;
import cr.logisticaportuaria.fleet.infrastructure.persistence.GeofenceRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cache of the active geofences per yard.
 *
 * <p>Geofences change rarely, so they are loaded on demand and refreshed every
 * {@code app.fleet.geofence-refresh-seconds} (60 s by default) instead of being read from the
 * database for every telemetry message.
 */
@Component
public class GeofenceCache {

    private final GeofenceRepository repository;
    private final Cache<UUID, List<Geofence>> cache;

    /**
     * Constructor.
     *
     * @param repository repository of geofences
     * @param properties configuration of the module
     */
    public GeofenceCache(GeofenceRepository repository, FleetProperties properties) {
        this.repository = repository;
        this.cache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(Duration.ofSeconds(properties.getGeofenceRefreshSeconds()))
                .build();
    }

    /**
     * @param yardId identifier of the yard
     * @return active geofences of the yard
     */
    public List<Geofence> forYard(UUID yardId) {
        return cache.get(yardId, repository::findActiveByYard);
    }

    /**
     * Invalidates every cached yard.
     */
    public void invalidate() {
        cache.invalidateAll();
    }
}
