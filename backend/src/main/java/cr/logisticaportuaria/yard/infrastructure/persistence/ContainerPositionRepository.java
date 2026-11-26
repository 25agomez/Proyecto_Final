package cr.logisticaportuaria.yard.infrastructure.persistence;

import cr.logisticaportuaria.yard.domain.ContainerPosition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository of the positions of the containers ({@code container_position}).
 */
public interface ContainerPositionRepository extends JpaRepository<ContainerPosition, UUID> {

    /**
     * @param containerId container placed
     * @return the active position of the container, when it exists
     */
    Optional<ContainerPosition> findByContainerIdAndActiveTrue(UUID containerId);

    /**
     * @param slotId slot of the position
     * @return the active position of the slot, when it exists
     */
    Optional<ContainerPosition> findBySlotIdAndActiveTrue(UUID slotId);

    /**
     * @param containerId container placed
     * @return the positions of the container, newest first
     */
    List<ContainerPosition> findByContainerIdOrderByPlacedAtDesc(UUID containerId);
}
