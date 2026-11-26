package cr.logisticaportuaria.shared.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Helper to resolve the actor of the current request (used by the audit trail
 * and by the timeline of the visit log).
 */
public final class CurrentActor {

    /** Name used when the request is not authenticated (for example, the MQTT subscriber). */
    public static final String SYSTEM = "sistema";

    private CurrentActor() {
        // utility class
    }

    /**
     * @return the name of the authenticated user, or {@code "sistema"} when there is none
     */
    public static String name() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return SYSTEM;
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? SYSTEM : name;
    }

    /**
     * @return true when the current request is authenticated
     */
    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated();
    }
}
