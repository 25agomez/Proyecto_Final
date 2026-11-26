package cr.logisticaportuaria.fleet.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * Trace of a container ({@code GET /containers/{code}/trace}).
 *
 * @param code     container code
 * @param timeline entries ordered by instant
 */
public record ContainerTraceResponse(String code, List<Entry> timeline) {

    /**
     * One entry of the timeline.
     *
     * @param type        GATE_EVENT, VISIT, YARD_POSITION or WORK_ORDER
     * @param at          instant of the entry
     * @param description explanation in Spanish (es-CR)
     * @param gateCode    gate involved, may be null
     * @param vehicleCode vehicle involved, may be null
     * @param location    location in the yard, may be null
     */
    public record Entry(String type, Instant at, String description, String gateCode,
            String vehicleCode, String location) {
    }
}
