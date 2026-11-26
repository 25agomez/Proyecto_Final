package cr.logisticaportuaria.yard.infrastructure.persistence;

import cr.logisticaportuaria.yard.domain.Gate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository of the gates ({@code gate}).
 */
public interface GateRepository extends JpaRepository<Gate, UUID> {

    /**
     * @param yardId yard of the gates
     * @return the gates of the yard
     */
    List<Gate> findByYardIdOrderByCode(UUID yardId);

    /**
     * @param yardId yard of the gate
     * @param code   code of the gate
     * @return the gate, when it exists
     */
    Optional<Gate> findByYardIdAndCode(UUID yardId, String code);
}
