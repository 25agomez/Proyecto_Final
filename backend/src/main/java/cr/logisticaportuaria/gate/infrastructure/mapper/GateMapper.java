package cr.logisticaportuaria.gate.infrastructure.mapper;

import cr.logisticaportuaria.gate.application.dto.ReviewTaskResponse;
import cr.logisticaportuaria.gate.application.dto.VisitResponse;
import cr.logisticaportuaria.gate.domain.ReviewTask;
import cr.logisticaportuaria.gate.domain.VisitLog;
import cr.logisticaportuaria.gate.domain.VisitLogEvent;
import cr.logisticaportuaria.gate.domain.VisitState;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Mapping between the entities of the gate module and their DTOs.
 */
@Mapper(componentModel = "spring")
public interface GateMapper {

    /**
     * @param visit    the visit
     * @param timeline stages of the visit
     * @return the DTO of the visit
     */
    @Mapping(target = "timeline", source = "timeline")
    VisitResponse toVisitResponse(VisitLog visit, List<VisitResponse.TimelineEntry> timeline);

    /**
     * @param event event of the timeline
     * @return the DTO of the timeline entry
     */
    @Mapping(target = "stage", source = "stage")
    VisitResponse.TimelineEntry toTimelineEntry(VisitLogEvent event);

    /**
     * @param task task of the manual review queue
     * @return the DTO of the task
     */
    ReviewTaskResponse toReviewTaskResponse(ReviewTask task);

    /**
     * Converts the stage stored as text into the enum of the state machine.
     *
     * @param stage text of the stage
     * @return the state (null when the text is empty)
     */
    default VisitState toState(String stage) {
        return stage == null || stage.isBlank() ? null : VisitState.valueOf(stage);
    }
}
