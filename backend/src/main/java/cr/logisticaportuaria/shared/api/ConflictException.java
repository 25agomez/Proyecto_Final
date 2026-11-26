package cr.logisticaportuaria.shared.api;

import org.springframework.http.HttpStatus;

/**
 * The requested change conflicts with the current state (HTTP 409): invalid
 * state machine transition, duplicated idempotency key or already resolved task.
 */
public class ConflictException extends ApiException {

    /**
     * Constructor.
     *
     * @param message human readable message
     */
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "urn:logisticaportuaria:error:conflict", message);
    }
}
