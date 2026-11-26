package cr.logisticaportuaria.yard.api;

import cr.logisticaportuaria.yard.api.dto.SlotResponse;
import cr.logisticaportuaria.yard.application.YardQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API of the slots of the yard.
 */
@RestController
@RequestMapping("/api/v1/yard")
public class SlotController {

    private final YardQueryService yardQueryService;

    /**
     * Constructor.
     *
     * @param yardQueryService service of the queries
     */
    public SlotController(YardQueryService yardQueryService) {
        this.yardQueryService = yardQueryService;
    }

    /**
     * Lists the slots with their current occupancy.
     *
     * @param yardId    yard of the slots (optional)
     * @param blockCode code of the block (optional)
     * @param onlyFree  whether only the free slots are returned
     * @return the slots
     */
    @GetMapping("/slots")
    public List<SlotResponse> list(
            @RequestParam(required = false) UUID yardId,
            @RequestParam(required = false) String blockCode,
            @RequestParam(defaultValue = "false") boolean onlyFree) {
        return yardQueryService.listSlots(yardId, blockCode, onlyFree);
    }
}
