package cr.logisticaportuaria.gate.infrastructure.mapper;

import cr.logisticaportuaria.gate.application.dto.GateEventSummary;
import cr.logisticaportuaria.gate.domain.GateEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Mapping of the readings of the gate.
 */
@Mapper(componentModel = "spring")
public interface GateEventMapper {

    /**
     * @param event reading of the gate
     * @param result result of the reconciliation
     * @param visitId visit of the digital logbook (may be null)
     * @param reason explanation of the result
     * @return the summary of the reading
     */
    @Mapping(target = "result", source = "result")
    @Mapping(target = "visitId", source = "visitId")
    @Mapping(target = "reason", source = "reason")
    GateEventSummary toSummary(GateEvent event, String result, java.util.UUID visitId, String reason);
}
