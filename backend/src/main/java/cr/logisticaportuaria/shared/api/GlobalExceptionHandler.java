package cr.logisticaportuaria.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Global handler of the errors: every failure leaves the API as a
 * {@code ProblemDetail} (RFC 7807) with the messages in Spanish.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles the business exceptions of the application.
     *
     * @param exception the exception
     * @param request  the current request
     * @return the problem detail response
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setType(URI.create(exception.type()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setTitle(titleFor(exception.status()));
        return ResponseEntity.status(exception.status()).body(problem);
    }

    /**
     * Handles the validation of the request bodies.
     *
     * @param exception the exception
     * @param request   the current request
     * @return the problem detail response
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        exception.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "La solicitud contiene datos inválidos.");
        problem.setType(URI.create("urn:logisticaportuaria:error:validation"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setTitle("Solicitud inválida");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Handles the validation of the query and path parameters.
     *
     * @param exception the exception
     * @param request   the current request
     * @return the problem detail response
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception,
            HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getConstraintViolations()
                .forEach(violation -> errors.put(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "La solicitud contiene parámetros inválidos.");
        problem.setType(URI.create("urn:logisticaportuaria:error:validation"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setTitle("Solicitud inválida");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Handles a malformed JSON body.
     *
     * @param exception the exception
     * @param request   the current request
     * @return the problem detail response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "El cuerpo de la solicitud no se pudo interpretar.");
        problem.setType(URI.create("urn:logisticaportuaria:error:malformed-body"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setTitle("Solicitud inválida");
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Handles the malformed values of the query and path parameters (for
     * example, an enum that does not exist).
     *
     * @param exception the exception
     * @param request   the current request
     * @return the problem detail response
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "El valor '%s' no es válido para el parámetro '%s'."
                        .formatted(exception.getValue(), exception.getName()));
        problem.setType(URI.create("urn:logisticaportuaria:error:validation"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setTitle("Solicitud inválida");
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Handles the violations of the integrity constraints of the database.
     *
     * @param exception the exception
     * @param request   the current request
     * @return the problem detail response
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException exception,
            HttpServletRequest request) {
        LOGGER.warn("Violación de integridad en {}: {}", request.getRequestURI(), exception.getMostSpecificCause()
                .getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "La operación viola una restricción de integridad de la base de datos.");
        problem.setType(URI.create("urn:logisticaportuaria:error:integrity"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setTitle("Conflicto de datos");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Handles the authentication failures.
     *
     * @param exception the exception
     * @param request   the current request
     * @return the problem detail response
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Las credenciales no son válidas para esta operación.");
        problem.setType(URI.create("urn:logisticaportuaria:error:unauthorized"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setTitle("No autenticado");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * Handles the authorization failures.
     *
     * @param exception the exception
     * @param request   the current request
     * @return the problem detail response
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "El rol del usuario no permite esta operación.");
        problem.setType(URI.create("urn:logisticaportuaria:error:forbidden"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setTitle("Acceso denegado");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    /**
     * Handles the failures that already carry their HTTP status (for example a
     * rejected credential). Without this handler the catch-all below would
     * turn them into a 500.
     *
     * @param exception the exception
     * @param request   the current request
     * @return the problem detail response
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException exception,
            HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String detail = exception.getReason() != null
                ? exception.getReason()
                : "La operación no se pudo completar.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(typeFor(status)));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setTitle(titleFor(status));
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * Handles any unexpected failure.
     *
     * @param exception the exception
     * @param request   the current request
     * @return the problem detail response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Error inesperado en {}", request.getRequestURI(), exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocurrió un error inesperado. Contacte al administrador con el identificador de la solicitud.");
        problem.setType(URI.create("urn:logisticaportuaria:error:internal"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setTitle("Error interno");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private String titleFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "Solicitud inválida";
            case UNAUTHORIZED -> "No autenticado";
            case FORBIDDEN -> "Acceso denegado";
            case NOT_FOUND -> "Recurso no encontrado";
            case CONFLICT -> "Conflicto de estado";
            case UNPROCESSABLE_ENTITY -> "Regla de negocio violada";
            default -> "Error";
        };
    }

    private String typeFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "urn:logisticaportuaria:error:validation";
            case UNAUTHORIZED -> "urn:logisticaportuaria:error:unauthorized";
            case FORBIDDEN -> "urn:logisticaportuaria:error:forbidden";
            case NOT_FOUND -> "urn:logisticaportuaria:error:not-found";
            case CONFLICT -> "urn:logisticaportuaria:error:conflict";
            case UNPROCESSABLE_ENTITY -> "urn:logisticaportuaria:error:business-rule";
            default -> "urn:logisticaportuaria:error:request";
        };
    }
}
