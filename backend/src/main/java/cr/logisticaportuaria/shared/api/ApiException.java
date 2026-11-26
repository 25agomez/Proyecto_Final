package cr.logisticaportuaria.shared.api;

import org.springframework.http.HttpStatus;

/**
 * Base class of the business exceptions. Every subclass carries the HTTP status
 * that the global handler maps to a {@code ProblemDetail} (RFC 7807).
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String type;

    /**
     * Constructor.
     *
     * @param status HTTP status of the error
     * @param type   URI of the error type
     * @param message human readable message (Spanish for the user)
     */
    protected ApiException(HttpStatus status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }

    /**
     * @return the HTTP status of the error
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * @return the URI that identifies the kind of error
     */
    public String type() {
        return type;
    }
}
