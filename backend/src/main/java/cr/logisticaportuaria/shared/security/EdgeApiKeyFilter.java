package cr.logisticaportuaria.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication of the ingestion endpoint for the edge service.
 *
 * <p>The edge publishes the readings either with a JWT (same resource server as
 * the rest of the API) or with the shared key {@code X-Edge-Api-Key}. When the
 * key is not configured the endpoint stays open, which is only acceptable in the
 * local development profile.
 */
@Component
public class EdgeApiKeyFilter extends OncePerRequestFilter {

    /** Header used by the edge to authenticate the ingestion. */
    public static final String HEADER = "X-Edge-Api-Key";

    private static final Logger LOGGER = LoggerFactory.getLogger(EdgeApiKeyFilter.class);
    private static final String INGEST_PATH = "/api/v1/gate-events";

    private final String apiKey;
    private final boolean openIngestion;

    /**
     * Constructor.
     *
     * @param apiKey         shared key of the edge service (empty when there is none)
     * @param openIngestion  whether the endpoint may be used without a key
     */
    public EdgeApiKeyFilter(
            @Value("${app.edge.api-key:}") String apiKey,
            @Value("${app.edge.open-ingestion:false}") boolean openIngestion) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.openIngestion = openIngestion;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!INGEST_PATH.equals(request.getRequestURI()) || !"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        if (request.getHeader("Authorization") != null) {
            // The bearer token is validated by the resource server.
            chain.doFilter(request, response);
            return;
        }

        String presented = request.getHeader(HEADER);
        if (apiKey.isEmpty() && openIngestion) {
            authenticateAsEdge(response, "edge");
            chain.doFilter(request, response);
            return;
        }

        if (apiKey.isEmpty()) {
            LOGGER.warn("Ingesta rechazada: no hay 'app.edge.api-key' configurada.");
            writeUnauthorized(response, "La ingesta requiere la clave del servicio de borde.");
            return;
        }

        if (presented == null || !presented.equals(apiKey)) {
            writeUnauthorized(response, "La clave del servicio de borde es inválida.");
            return;
        }

        authenticateAsEdge(response, "edge");
        chain.doFilter(request, response);
    }

    private void authenticateAsEdge(HttpServletResponse response, String actor) {
        var authentication = new UsernamePasswordAuthenticationToken(
                actor,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_EDGE")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void writeUnauthorized(HttpServletResponse response, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setType(URI.create("urn:logisticaportuaria:error:unauthorized"));
        problem.setTitle("No autenticado");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"urn:logisticaportuaria:error:unauthorized","title":"No autenticado",\
                "status":401,"detail":"%s"}"""
                .formatted(detail.replace("\"", "'")));
    }
}
