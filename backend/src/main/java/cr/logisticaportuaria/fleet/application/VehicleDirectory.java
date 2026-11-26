package cr.logisticaportuaria.fleet.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cr.logisticaportuaria.fleet.domain.VehicleInfo;
import cr.logisticaportuaria.fleet.infrastructure.persistence.FleetVehicleRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * In-memory directory of the master data of the vehicles.
 */
@Component
public class VehicleDirectory {

    private static final String ALL = "all";

    private final FleetVehicleRepository repository;
    private final Cache<String, Map<UUID, VehicleInfo>> cache;

    /**
     * Constructor.
     *
     * @param repository repository of vehicles
     * @param properties configuration of the module
     */
    public VehicleDirectory(FleetVehicleRepository repository, FleetProperties properties) {
        this.repository = repository;
        this.cache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(Duration.ofSeconds(properties.getGeofenceRefreshSeconds()))
                .build();
    }

    /**
     * @param vehicleId identifier of the vehicle
     * @return master data of the vehicle, when it exists and is active
     */
    public Optional<VehicleInfo> byId(UUID vehicleId) {
        return Optional.ofNullable(all().get(vehicleId));
    }

    /**
     * @return every active vehicle
     */
    public List<VehicleInfo> vehicles() {
        return List.copyOf(all().values());
    }

    /**
     * Invalidates the directory.
     */
    public void invalidate() {
        cache.invalidateAll();
    }

    private Map<UUID, VehicleInfo> all() {
        return cache.get(ALL, key -> repository.findAll().stream()
                .collect(Collectors.toMap(VehicleInfo::id, Function.identity())));
    }
}
