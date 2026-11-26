package cr.logisticaportuaria.fleet.application;

import cr.logisticaportuaria.fleet.domain.AlertType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Mutable per-vehicle context of the alert rules.
 *
 * <p>Keeps what cannot be derived from a single message: since when the vehicle is stopped,
 * which zones contained the previous position and when every alert was raised last (alert
 * cooldown). The context only lives in memory: the alerts themselves are informational and
 * the durable state is the position series (ADR-014).
 */
@Component
public class AlertStateStore {

    private final Map<UUID, AlertContext> contexts = new ConcurrentHashMap<>();

    /**
     * @param vehicleId identifier of the vehicle
     * @return context of the vehicle, created on first use
     */
    public AlertContext context(UUID vehicleId) {
        return contexts.computeIfAbsent(vehicleId, id -> new AlertContext());
    }

    /**
     * Forgets the context of a vehicle.
     *
     * @param vehicleId identifier of the vehicle
     */
    public void forget(UUID vehicleId) {
        contexts.remove(vehicleId);
    }

    /**
     * Mutable alert context.
     */
    public static final class AlertContext {

        private Instant stoppedSince;
        private Set<UUID> zones = new LinkedHashSet<>();
        private boolean initialized;
        private boolean silent;
        private final Map<AlertType, Instant> lastRaised = new HashMap<>();

        public Instant stoppedSince() {
            return stoppedSince;
        }

        public void stoppedSince(Instant value) {
            this.stoppedSince = value;
        }

        public Set<UUID> zones() {
            return zones;
        }

        public void zones(Set<UUID> value) {
            this.zones = new LinkedHashSet<>(value);
            this.initialized = true;
        }

        public boolean initialized() {
            return initialized;
        }

        public boolean silent() {
            return silent;
        }

        public void silent(boolean value) {
            this.silent = value;
        }

        /**
         * @param type alert type
         * @param now instant of the new alert
         * @param cooldownMinutes minutes before the same alert can be repeated
         * @return whether the alert can be emitted
         */
        public boolean canRaise(AlertType type, Instant now, int cooldownMinutes) {
            Instant last = lastRaised.get(type);
            if (last == null || last.plusSeconds(cooldownMinutes * 60L).isBefore(now)) {
                lastRaised.put(type, now);
                return true;
            }
            return false;
        }

        /**
         * @return alerts raised so far, for diagnostics
         */
        public List<AlertType> raisedTypes() {
            return new ArrayList<>(lastRaised.keySet());
        }
    }
}
