package cr.logisticaportuaria.yard.infrastructure.persistence;

import cr.logisticaportuaria.yard.domain.YardBlock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository of the blocks of the yard ({@code yard_block}).
 */
public interface YardBlockRepository extends JpaRepository<YardBlock, UUID> {

    /**
     * @param yardId yard of the blocks
     * @return the blocks of the yard
     */
    List<YardBlock> findByYardIdOrderByCode(UUID yardId);
}
