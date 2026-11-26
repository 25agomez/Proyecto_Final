package cr.logisticaportuaria.yard.application;

import cr.logisticaportuaria.shared.api.NotFoundException;
import cr.logisticaportuaria.shared.api.PageResponse;
import cr.logisticaportuaria.yard.api.dto.ContainerResponse;
import cr.logisticaportuaria.yard.api.dto.SlotResponse;
import cr.logisticaportuaria.yard.domain.Container;
import cr.logisticaportuaria.yard.domain.ContainerPosition;
import cr.logisticaportuaria.yard.domain.ContainerStatus;
import cr.logisticaportuaria.yard.domain.YardBlock;
import cr.logisticaportuaria.yard.domain.YardSlot;
import cr.logisticaportuaria.yard.infrastructure.mapper.YardMapper;
import cr.logisticaportuaria.yard.infrastructure.persistence.ContainerPositionRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.ContainerRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.YardBlockRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.YardSlotRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultation of the containers and the slots of the yard.
 */
@Service
public class YardQueryService {

    private final ContainerRepository containerRepository;
    private final ContainerPositionRepository containerPositionRepository;
    private final YardSlotRepository yardSlotRepository;
    private final YardBlockRepository yardBlockRepository;
    private final YardMapper mapper;

    /**
     * Constructor.
     *
     * @param containerRepository       repository of the containers
     * @param containerPositionRepository repository of the positions
     * @param yardSlotRepository        repository of the slots
     * @param yardBlockRepository       repository of the blocks
     * @param mapper                    mapper of the DTOs
     */
    public YardQueryService(ContainerRepository containerRepository,
            ContainerPositionRepository containerPositionRepository,
            YardSlotRepository yardSlotRepository,
            YardBlockRepository yardBlockRepository,
            YardMapper mapper) {
        this.containerRepository = containerRepository;
        this.containerPositionRepository = containerPositionRepository;
        this.yardSlotRepository = yardSlotRepository;
        this.yardBlockRepository = yardBlockRepository;
        this.mapper = mapper;
    }

    /**
     * Lists the containers with the given filters.
     *
     * @param codePart   part of the BIC code (may be null)
     * @param status     status of the container (may be null)
     * @param clientCode client that owns the container (may be null)
     * @param from       lower bound of the ETD (may be null)
     * @param to         upper bound of the ETD (may be null)
     * @param pageable   pagination
     * @return the page of containers
     */
    @Transactional(readOnly = true)
    public PageResponse<ContainerResponse> listContainers(String codePart,
            ContainerStatus status,
            String clientCode,
            Instant from,
            Instant to,
            Pageable pageable) {
        Page<Container> page = containerRepository.search(codePart, status, clientCode, from, to, pageable);
        return PageResponse.of(page.map(this::toResponse));
    }

    /**
     * Returns a container by its BIC code.
     *
     * @param code BIC code
     * @return the container
     */
    @Transactional(readOnly = true)
    public ContainerResponse getContainer(String code) {
        Container container = containerRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("contenedor", code));
        return toResponse(container);
    }

    /**
     * Lists the slots of a yard with their occupancy.
     *
     * @param yardId    yard of the slots (may be null)
     * @param blockCode code of the block (may be null)
     * @param onlyFree  whether only the free slots are returned
     * @return the slots
     */
    @Transactional(readOnly = true)
    public List<SlotResponse> listSlots(UUID yardId, String blockCode, boolean onlyFree) {
        return yardSlotRepository.searchSlots(yardId, blockCode, onlyFree).stream()
                .map(mapper::toSlotResponse)
                .toList();
    }

    private ContainerResponse toResponse(Container container) {
        ContainerResponse response = mapper.toContainerResponse(container);
        Optional<ContainerPosition> position = containerPositionRepository
                .findByContainerIdAndActiveTrue(container.getId());
        if (position.isPresent()) {
            response = withSlot(response, position.get().getSlotId());
        }
        return response;
    }

    private ContainerResponse withSlot(ContainerResponse response, UUID slotId) {
        YardSlot slot = yardSlotRepository.findById(slotId).orElse(null);
        if (slot == null) {
            return response;
        }
        String blockCode = yardBlockRepository.findById(slot.getBlockId())
                .map(YardBlock::getCode)
                .orElse(null);
        SlotResponse slotResponse = new SlotResponse(
                slot.getId(),
                blockCode,
                slot.getBay(),
                slot.getRow(),
                slot.getTier(),
                slot.getSizeFt(),
                slot.getSlotType().name(),
                slot.isHasPower(),
                null,
                true,
                response.code());
        return new ContainerResponse(
                response.id(),
                response.code(),
                response.sizeFt(),
                response.containerType(),
                response.status(),
                response.weightKg(),
                response.clientCode(),
                response.eta(),
                response.etd(),
                response.requiresPower(),
                slotResponse);
    }
}
