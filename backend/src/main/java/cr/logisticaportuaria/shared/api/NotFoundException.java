package cr.logisticaportuaria.shared.api;

import org.springframework.http.HttpStatus;

/**
 * The requested resource does not exist (HTTP 404).
 */
public class NotFoundException extends ApiException {

    /**
     * Constructor.
     *
     * @param message human readable message
     */
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "urn:logisticaportuaria:error:not-found", message);
    }

    /**
     * Constructor with the name of the entity and its identifier.
     *
     * @param entity name of the entity
     * @param id     identifier
     */
    public NotFoundException(String entity, Object id) {
        super(
                HttpStatus.NOT_FOUND,
                "urn:logisticaportuaria:error:not-found",
                "No se encontró " + entity + " con identificador " + id + ".");
    }
}
