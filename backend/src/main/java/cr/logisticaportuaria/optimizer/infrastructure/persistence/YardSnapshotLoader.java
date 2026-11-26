package cr.logisticaportuaria.optimizer.infrastructure.persistence;

import cr.logisticaportuaria.optimizer.application.OptimizerProperties;
import cr.logisticaportuaria.optimizer.domain.ContainerUnit;
import cr.logisticaportuaria.optimizer.domain.DistanceModel;
import cr.logisticaportuaria.optimizer.domain.SlotNode;
import cr.logisticaportuaria.optimizer.domain.YardSnapshot;
import cr.logisticaportuaria.shared.api.BusinessRuleException;
import cr.logisticaportuaria.yard.domain.Container;
import cr.logisticaportuaria.yard.domain.ContainerType;
import cr.logisticaportuaria.yard.domain.SlotType;
import cr.logisticaportuaria.yard.infrastructure.persistence.ContainerRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.SlotView;
import cr.logisticaportuaria.yard.infrastructure.persistence.YardSlotRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link YardSnapshot} of the optimizer from the tables of the yard module.
 *
 * <p>It only reads: the schema and the repositories of the {@code yard} package belong to
 * another agent, so the optimizer consumes the existing projection {@code searchSlots}
 * (slots with their active position) and the container catalog. Two derived values are
 * computed here: the maximum tier of each block (the projection does not carry it) and the
 * distance to the gate ({@link DistanceModel}).
 */
@Component
public class YardSnapshotLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(YardSnapshotLoader.class);

    private final YardSlotRepository slotRepository;
    private final ContainerRepository containerRepository;
    private final OptimizerProperties properties;

    /**
     * Constructor.
     *
     * @param slotRepository      repository of the slots
     * @param containerRepository repository of the containers
     * @param properties          configuration of the optimizer
     */
    public YardSnapshotLoader(YardSlotRepository slotRepository, ContainerRepository containerRepository,
            OptimizerProperties properties) {
        this.slotRepository = slotRepository;
        this.containerRepository = containerRepository;
        this.properties = properties;
    }

    /**
     * @return the snapshot of the configured yard (or of every yard when none is configured)
     * @throws BusinessRuleException when the yard has no slots
     */
    public YardSnapshot load() {
        UUID yardId = properties.getYardId();
        List<SlotView> views = slotRepository.searchSlots(yardId, null, false);
        if (views.isEmpty()) {
            throw new BusinessRuleException("El patio no tiene slots configurados; cargue el patio antes de optimizar.");
        }
        Map<String, Container> containers = new HashMap<>();
        for (Container container : containerRepository.findAll()) {
            containers.putIfAbsent(normalize(container.getCode()), container);
        }
        Map<String, Integer> maxTierByBlock = new HashMap<>();
        for (SlotView view : views) {
            maxTierByBlock.merge(view.getBlockCode(), view.getTier(), Math::max);
        }
        List<SlotNode> slots = new ArrayList<>(views.size());
        List<ContainerUnit> placed = new ArrayList<>();
        for (SlotView view : views) {
            SlotType slotType = parseSlotType(view.getSlotType());
            SlotNode slot = new SlotNode(
                    view.getId(),
                    view.getBlockCode(),
                    view.getBay(),
                    view.getRow(),
                    view.getTier(),
                    view.getSizeFt(),
                    slotType,
                    Boolean.TRUE.equals(view.getHasPower()),
                    maxTierByBlock.getOrDefault(view.getBlockCode(), view.getTier()),
                    DistanceModel.distanceOf(view.getBlockCode(), view.getBay(), view.getRow(), view.getTier()));
            slots.add(slot);
            if (view.getContainerCode() != null) {
                placed.add(unitOf(view, slot, slotType, containers.get(normalize(view.getContainerCode()))));
            }
        }
        return YardSnapshot.of(slots, placed);
    }

    private ContainerUnit unitOf(SlotView view, SlotNode slot, SlotType slotType, Container container) {
        if (container == null) {
            LOGGER.warn("El slot {} apunta al contenedor {} que no existe en el catálogo; se trata como carga seca.",
                    slot.code(), view.getContainerCode());
            return new ContainerUnit(view.getContainerCode(), slot.sizeFt(), containerTypeOf(slotType), null, null,
                    null, null, slotType == SlotType.reefer, null, view.getId());
        }
        return new ContainerUnit(
                container.getCode(),
                container.getSizeFt(),
                container.getContainerType(),
                container.getWeightKg() == null ? null : container.getWeightKg().doubleValue(),
                container.getEtd(),
                null,
                container.getClientCode(),
                container.isRequiresPower(),
                container.getImoClass(),
                view.getId());
    }

    private static ContainerType containerTypeOf(SlotType slotType) {
        return switch (slotType) {
            case dry -> ContainerType.dry;
            case reefer -> ContainerType.reefer;
            case hazmat -> ContainerType.hazmat;
        };
    }

    private static SlotType parseSlotType(String value) {
        return SlotType.valueOf(value.trim().toLowerCase(Locale.ROOT));
    }

    private static String normalize(String code) {
        return code == null ? "" : code.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }
}
