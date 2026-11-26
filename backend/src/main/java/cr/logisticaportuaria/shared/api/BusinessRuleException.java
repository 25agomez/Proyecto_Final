package cr.logisticaportuaria.shared.api;

import org.springframework.http.HttpStatus;

/**
 * A business rule was violated (HTTP 422): the request is well formed but it
 * cannot be processed with the current data.
 */
public class BusinessRuleException extends ApiException {

    /**
     * Constructor.
     *
     * @param message human readable message
     */
    public BusinessRuleException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "urn:logisticaportuaria:error:business-rule", message);
    }
}
