package cr.logisticaportuaria.optimizer.congestion.api;

import cr.logisticaportuaria.optimizer.congestion.application.CongestionService;
import cr.logisticaportuaria.optimizer.congestion.application.dto.CongestionForecastResponse;
import cr.logisticaportuaria.optimizer.congestion.application.dto.HeatmapResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API of the congestion forecast.
 *
 * <p>Implements the two operations of the contract: the forecast of a gate for a horizon of
 * 15, 30 or 60 minutes and the historical heatmap.
 */
@RestController
@RequestMapping("/api/v1/congestion")
public class CongestionController {

    private static final String READ_ROLES = "hasAnyRole('OPERADOR_PUERTA', 'SUPERVISOR', 'ADMIN', 'AUDITOR')";

    private final CongestionService service;

    /**
     * Constructor.
     *
     * @param service service of the congestion forecast
     */
    public CongestionController(CongestionService service) {
        this.service = service;
    }

    /**
     * Forecast of the congestion of a gate.
     *
     * @param gateId  gate
     * @param horizon horizon in minutes (15, 30 or 60; default 15)
     * @return the forecast, with bounds, drivers and recommendations
     */
    @GetMapping("/forecast")
    @PreAuthorize(READ_ROLES)
    public CongestionForecastResponse forecast(@RequestParam UUID gateId,
            @RequestParam(defaultValue = "15") int horizon) {
        return service.forecast(gateId, horizon);
    }

    /**
     * Historical heatmap of the wait per day of the week and hour.
     *
     * @param from   inclusive start (ISO-8601)
     * @param to     exclusive end (ISO-8601)
     * @param gateId optional gate; without it the heatmap aggregates every active gate
     * @return the heatmap
     */
    @GetMapping("/heatmap")
    @PreAuthorize(READ_ROLES)
    public HeatmapResponse heatmap(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID gateId) {
        return service.heatmap(gateId, from, to);
    }
}
